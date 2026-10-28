/* Send synthetic KeyPress/KeyRelease (and optional mouse) events to every
 * toplevel window on an X display via XSendEvent - enough to drive Godot
 * games on a headless Xvfb for gameplay screenshots. */
#include <X11/Xlib.h>
#include <X11/keysym.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static void send_key(Display *d, Window w, KeySym ks, unsigned long hold_ms) {
        XEvent ev;
        KeyCode kc = XKeysymToKeycode(d, ks);
        if (!kc) return;
        memset(&ev, 0, sizeof(ev));
        ev.xkey.type = KeyPress;
        ev.xkey.window = w;
        ev.xkey.root = DefaultRootWindow(d);
        ev.xkey.subwindow = None;
        ev.xkey.time = CurrentTime;
        ev.xkey.x = 640; ev.xkey.y = 360;
        ev.xkey.x_root = 640; ev.xkey.y_root = 360;
        ev.xkey.state = 0;
        ev.xkey.keycode = kc;
        ev.xkey.same_screen = True;
        XSendEvent(d, w, False, KeyPressMask|KeyReleaseMask, &ev);
        XFlush(d);
        usleep(hold_ms * 1000);          // let the 5 fps game OBSERVE the key down
        ev.xkey.type = KeyRelease;
        XSendEvent(d, w, False, KeyPressMask|KeyReleaseMask, &ev);
        XFlush(d);
        usleep(150 * 1000);
}

static void send_click(Display *d, Window w, int x, int y, unsigned long delay_ms) {
        XEvent ev;
        memset(&ev, 0, sizeof(ev));
        ev.xbutton.type = ButtonPress;
        ev.xbutton.window = w;
        ev.xbutton.root = DefaultRootWindow(d);
        ev.xbutton.subwindow = None;
        ev.xbutton.time = CurrentTime;
        ev.xbutton.x = x; ev.xbutton.y = y;
        ev.xbutton.x_root = x; ev.xbutton.y_root = y;
        ev.xbutton.state = 0;
        ev.xbutton.button = 1;
        ev.xbutton.same_screen = True;
        XSendEvent(d, w, False, KeyPressMask|KeyReleaseMask, &ev);
        ev.xbutton.type = ButtonRelease;
        XSendEvent(d, w, False, KeyPressMask|KeyReleaseMask, &ev);
        XFlush(d);
        usleep(delay_ms * 1000);
}

static void collect_windows(Display *d, Window root, Window *out, int *n, int cap) {
        Window parent, *children = NULL;
        unsigned int nch = 0;
        if (*n >= cap) return;
        if (XQueryTree(d, root, &root, &parent, &children, &nch)) {
                for (unsigned int i = 0; i < nch && *n < cap; i++) {
                        XWindowAttributes wa;
                        if (XGetWindowAttributes(d, children[i], &wa) && wa.map_state == IsViewable) {
                                out[(*n)++] = children[i];
                        }
                        collect_windows(d, children[i], out, n, cap);
                }
        }
        if (children) XFree(children);
}

int main(int argc, char **argv) {
        if (argc < 3) {
                fprintf(stderr, "usage: xkeys DISPLAY \"seq\"\n"
                        "seq: comma-separated tokens: L R U D A(space) N(enter)\n"
                        "     cXxY = click at X,Y; sN = sleep N ms; kN = repeat last key N times\n");
                return 1;
        }
        Display *d = XOpenDisplay(argv[1]);
        if (!d) { fprintf(stderr, "cannot open display %s\n", argv[1]); return 1; }
        Window wins[32];
        int n = 0;
        int tries = 0;
        while (n == 0 && tries < 120) {   // wait up to 30s for the game window
                collect_windows(d, DefaultRootWindow(d), wins, &n, 32);
                if (n == 0) { usleep(500000); tries++; }
        }
        if (n == 0) { fprintf(stderr, "no viewable windows\n"); return 1; }
        fprintf(stderr, "sending to %d window(s)\n", n);
        char *seq = strdup(argv[2]);
        KeySym last = 0;
        for (char *tok = strtok(seq, ","); tok; tok = strtok(NULL, ",")) {
                KeySym ks = 0;
                unsigned long delay = 600;
                if (!strcmp(tok, "L")) ks = XK_Left;
                else if (!strcmp(tok, "R")) ks = XK_Right;
                else if (!strcmp(tok, "U")) ks = XK_Up;
                else if (!strcmp(tok, "D")) ks = XK_Down;
                else if (!strcmp(tok, "A")) ks = XK_space;
                else if (!strcmp(tok, "N")) ks = XK_Return;
                else if (tok[0] == 'c') {
                        int x = 0, y = 0;
                        sscanf(tok + 1, "%dx%d", &x, &y);
                        for (int i = 0; i < n; i++) send_click(d, wins[i], x, y, 500);
                        continue;
                } else if (tok[0] == 's') {
                        usleep(atoi(tok + 1) * 1000);
                        continue;
                } else if (tok[0] == 'k') {
                        int times = atoi(tok + 1);
                        for (int r = 0; r < times; r++)
                                for (int i = 0; i < n; i++) send_key(d, wins[i], last, delay);
                        continue;
                } else continue;
                last = ks;
                for (int i = 0; i < n; i++) send_key(d, wins[i], ks, delay);
        }
        XCloseDisplay(d);
        return 0;
}
