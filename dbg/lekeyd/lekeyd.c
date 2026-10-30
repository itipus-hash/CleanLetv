/*
 * lekeyd -- LeEco "Le" button -> app library bridge.
 *
 * Why a native root daemon instead of an app:
 *   The firmware (TvMasterManager, inside boot.oat) force-stops every package that holds an
 *   accessibility service within ~0.5s of another app reaching the foreground. Verified with
 *   both com.gathin.tvhome and a dedicated one-service package (com.gathin.tvkeys): the
 *   process is killed, `dumpsys accessibility` empties out, and the service is NOT rebound
 *   because force-stop leaves the package in the stopped state. So no App-level global key
 *   listener can survive on this box. A plain process started at boot is immune.
 *
 * What it does:
 *   Opens every /dev/input/event* node and watches for EV_KEY with the Le scancode
 *   (189 = LE) going down, then runs
 *       am start -f 0x10000000 -a com.gathin.tvhome.action.TOGGLE_LIBRARY \
 *                -n com.gathin.tvhome/.AppLibraryActivity -d tvhome://toggle/<uptime>
 *   The activity is singleTop in a task of its own and its window is translucent, so the library
 *   appears over whatever app was in front instead of bringing the desktop forward; finishing it
 *   lands back on that app.
 *
 *   Scancode 377 (LETV, the share/screenshot button under the Settings key) is deliberately NOT
 *   watched: its keylayout entry is left as LETV so the firmware's own takeScreenshot keeps
 *   working. Only the Le button (189) opens the library.
 *
 *   The unique data URI is what makes every intent distinct -- an identical intent would be
 *   dropped by the activity manager instead of being delivered again.
 *
 *   None of that happens while the desktop is already in front: MainActivity keeps its pid in
 *   files/foreground while resumed (see home_in_front), and then the key is handled in-process
 *   with no activity round trip at all.
 *
 *   Two helpers run around the toggle (leplay.sh): one remembers which app was playing just
 *   before the overlay covers it, the other sends a media-play key after it closes. A translucent
 *   activity still stops the activity underneath, so a video app pauses; nothing can avoid that
 *   from here, but the playback can be handed back.
 *
 * Scancodes are matched, not keycodes, so the keylayout remap (LE -> F5) does not
 * matter here. The remap exists only so the target app does not receive MENU.
 *
 * Devices are rescanned periodically so a Bluetooth remote that connects later (and lands
 * on a different event node) is picked up without restarting the daemon.
 */

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define MAX_DEV     32
/* The Le button only. 377 (LETV, the share/screenshot button) is left to the firmware. */
#define SCAN_LE     189
#define THROTTLE_MS 400        /* ignore repeats / double reports of one physical press */
#define RESCAN_MS   5000
/* Exit code the wrapper uses to tell "someone else owns the button" from "I crashed". */
#define EXIT_ALREADY_RUNNING 2

/* The app writes this in onPause, i.e. the moment the library goes away. See leplay.sh. */
#define STATE_DIR   "/data/data/com.gathin.tvhome/files"
#define CLOSED_FLAG STATE_DIR "/library_closed"
#define PLAY_HELPER "/system/bin/leplay.sh"

static int fds[MAX_DEV];
static char paths[MAX_DEV][32];
static int nfds;

static const char *LOG_PATH = "/data/local/tmp/lekeyd.log";

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void logline(const char *fmt, ...) {
    /* Deliberately tiny: a fixed 8 KB ring-ish log is enough to debug a boot-time daemon. */
    static int checked;
    if (!checked) {
        checked = 1;
        FILE *f = fopen(LOG_PATH, "r");
        if (f) {
            fseek(f, 0, SEEK_END);
            long sz = ftell(f);
            fclose(f);
            if (sz > 64 * 1024) truncate(LOG_PATH, 0);
        }
    }
    FILE *f = fopen(LOG_PATH, "a");
    if (!f) return;
    fprintf(f, "[%ld] ", now_ms());
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

/*
 * True when another copy of this daemon is already reading the devices. Two instances would each
 * run `am start` for one press and toggle the library twice, so the second one exits instead.
 *
 * This is not paranoia: /init.rc has TWO services that run install-recovery.sh
 * (flash_recovery at line 680 and installLetvMisc --misc at line 743), so the boot hook that
 * starts us runs twice per boot and both copies appear within a few tens of milliseconds.
 * Compared against the first argv token, so the `sh /system/bin/lekeyd.sh` wrapper - whose
 * cmdline also contains "lekeyd" - is not mistaken for an instance.
 */
static int another_instance(void) {
    DIR *proc = opendir("/proc");
    if (proc == NULL) return 0;
    int self = getpid();
    int found = 0;
    struct dirent *entry;
    while (!found && (entry = readdir(proc)) != NULL) {
        if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
        int pid = atoi(entry->d_name);
        if (pid == self) continue;
        char path[64];
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        char buf[128];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';                       /* cmdline is NUL separated: argv[0] ends here */
        found = strcmp(buf, "/system/bin/lekeyd") == 0 || strcmp(buf, "lekeyd") == 0;
    }
    closedir(proc);
    return found;
}

/*
 * True while the desktop itself is on screen, as reported by MainActivity: it writes its pid into
 * files/foreground in onResume and deletes the file in onPause. Home handles the key on its own then
 * (dispatchKeyEvent, and HomeKeyService in the same process), so firing an intent as well would only
 * pause and resume Home around a toggle it already did.
 *
 * The pid is checked, not just the file: a process killed while in front leaves the file behind, and
 * a stale marker must not make the button dead in every other app.
 */
static int home_in_front(void) {
    const char *paths[] = {
            "/data/data/com.gathin.tvhome/files/foreground",
            "/data/user/0/com.gathin.tvhome/files/foreground",
    };
    for (unsigned i = 0; i < sizeof(paths) / sizeof(paths[0]); i++) {
        int fd = open(paths[i], O_RDONLY);
        if (fd < 0) continue;
        char buf[24];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) return 0;
        buf[n] = '\0';
        int pid = atoi(buf);
        if (pid <= 0) return 0;
        char proc[32];
        snprintf(proc, sizeof(proc), "/proc/%d", pid);
        return access(proc, F_OK) == 0;
    }
    return 0;
}

static void open_devices(void) {
    char path[32];
    for (int i = 0; i < 32 && nfds < MAX_DEV; i++) {
        snprintf(path, sizeof(path), "/dev/input/event%d", i);
        int already = 0;
        for (int k = 0; k < nfds; k++) {
            if (strcmp(paths[k], path) == 0) { already = 1; break; }
        }
        if (already) continue;
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        snprintf(paths[nfds], sizeof(paths[nfds]), "%s", path);
        fds[nfds] = fd;
        nfds++;
        logline("watching %s", path);
    }
}

static void drop_device(int idx) {
    logline("closing %s", paths[idx]);
    close(fds[idx]);
    fds[idx] = fds[nfds - 1];
    memcpy(paths[idx], paths[nfds - 1], sizeof(paths[idx]));
    nfds--;
}

/*
 * Runs `sh leplay.sh <mode>` in a child of our own. Both calls are deliberately fire-and-forget:
 * they shell out to dumpsys (a few hundred milliseconds) and must not stall the input loop, and a
 * child of a root daemon survives the force-stop that hits the app package moments later.
 *
 * The input descriptors are closed first. A second reader on the same evdev node would split the
 * event stream with us and swallow half the presses.
 */
static void spawn_helper(const char *mode) {
    pid_t pid = fork();
    if (pid != 0) return;
    for (int i = 0; i < nfds; i++) close(fds[i]);
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) {
        dup2(devnull, STDIN_FILENO);
        dup2(devnull, STDOUT_FILENO);
        dup2(devnull, STDERR_FILENO);
        if (devnull > STDERR_FILENO) close(devnull);
    }
    execl("/system/bin/sh", "sh", PLAY_HELPER, mode, (char *)NULL);
    _exit(127);
}

static void trigger(void) {
    char tsbuf[24];
    char uri[48];
    snprintf(tsbuf, sizeof(tsbuf), "%ld", now_ms() & 0x7fffffff);
    /* Note who is playing before the overlay covers them, so the close can restore it. */
    spawn_helper("was");
    /* The data URI keeps every intent distinct. Extras are not enough: the activity manager
     * compares intents with filterEquals(), which ignores them, and then only brings the task
     * to the front instead of delivering a new intent. */
    snprintf(uri, sizeof(uri), "tvhome://toggle/%s", tsbuf);
    pid_t pid = fork();
    if (pid != 0) return;                     /* parent: nothing else to do */
    for (int i = 0; i < nfds; i++) close(fds[i]);
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) {
        dup2(devnull, STDIN_FILENO);
        dup2(devnull, STDOUT_FILENO);
        dup2(devnull, STDERR_FILENO);
        if (devnull > STDERR_FILENO) close(devnull);
    }
    execl("/system/bin/am", "am", "start",
          "-f", "268435456",                   /* FLAG_ACTIVITY_NEW_TASK */
          "-a", "com.gathin.tvhome.action.TOGGLE_LIBRARY",
          "-n", "com.gathin.tvhome/.AppLibraryActivity",
          "-d", uri,
          "--ei", "ts", tsbuf, (char *)NULL);
    _exit(127);
}

int main(void) {
    signal(SIGCHLD, SIG_IGN);                  /* reap the am children automatically */
    signal(SIGHUP, SIG_IGN);

    /* Let the sibling instance (if any) finish exec'ing, then look again: the two boot hooks
     * can be a few milliseconds apart, and exec sets the cmdline the scan looks at. */
    if (another_instance()) _exit(EXIT_ALREADY_RUNNING);
    usleep(300 * 1000);
    if (another_instance()) {
        logline("another lekeyd is already running, standing down (pid %d)", getpid());
        _exit(EXIT_ALREADY_RUNNING);
    }

    logline("lekeyd started (pid %d)", getpid());
    /* A flag left behind by a crash would otherwise fire the playback guard at boot. */
    unlink(CLOSED_FLAG);
    open_devices();
    if (nfds == 0) {
        logline("no input devices, sleeping and retrying");
    }

    long last = 0;
    long last_scan = now_ms();

    for (;;) {
        fd_set set;
        FD_ZERO(&set);
        int maxfd = -1;
        for (int i = 0; i < nfds; i++) {
            FD_SET(fds[i], &set);
            if (fds[i] > maxfd) maxfd = fds[i];
        }

        struct timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = 250 * 1000;              /* short so the close flag is noticed promptly */
        int ready = select(maxfd + 1, &set, NULL, NULL, &tv);
        if (ready < 0 && errno != EINTR) {
            logline("select failed: %d", errno);
            usleep(200 * 1000);
        }

        /* The library was closed: give the app behind it its playback back. The helper decides
         * whether that is wanted - same app as before, and still paused. */
        if (access(CLOSED_FLAG, F_OK) == 0) {
            unlink(CLOSED_FLAG);
            logline("library closed -> playback guard");
            spawn_helper("resume");
        }

        for (int i = nfds - 1; i >= 0 && ready > 0; i--) {
            if (!FD_ISSET(fds[i], &set)) continue;
            struct input_event ev;
            ssize_t n = read(fds[i], &ev, sizeof(ev));
            if (n == sizeof(ev)) {
                if (ev.type == EV_KEY && ev.value == 1 &&
                    ev.code == SCAN_LE) {
                    long t = now_ms();
                    if (t - last >= THROTTLE_MS) {
                        last = t;
                        if (home_in_front()) {
                            logline("scancode %d, home in front -> left to the desktop", ev.code);
                        } else {
                            logline("scancode %d on %s -> toggle", ev.code, paths[i]);
                            trigger();
                        }
                    }
                }
            } else if (n < 0 && errno != EAGAIN && errno != EINTR) {
                drop_device(i);                /* device went away (BT remote disconnected) */
            } else if (n == 0) {
                drop_device(i);
            }
        }

        if (now_ms() - last_scan >= RESCAN_MS) {
            last_scan = now_ms();
            open_devices();
        }
    }
    return 0;
}
