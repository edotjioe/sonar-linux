#!/usr/bin/env python3
# Sonar windows (WebKitGTK, no browser chrome). One running instance serves both:
#   sonar-window.py           -> the normal mixer window
#   sonar-window.py --toggle  -> show/hide the full-screen overlay (bound to a global hotkey)
# The mixer's OBS button (window.open) opens the OBS page in its own window.
import json
import sys
import urllib.request

import gi
gi.require_version('Gtk', '3.0')
gi.require_version('Gdk', '3.0')
gi.require_version('WebKit2', '4.1')
gi.require_version('GtkLayerShell', '0.1')
from gi.repository import Gdk, Gio, GLib, Gtk, GtkLayerShell, WebKit2

URL = 'http://localhost:8080/'
OVERLAY_URL = URL + '?overlay=1'


def retry(view, _event, uri, err):
    if err.code == WebKit2.NetworkError.CANCELLED:
        return False  # we navigated away ourselves (e.g. hiding the overlay)
    # backend may still be booting (e.g. right after login): retry instead of showing an error page
    GLib.timeout_add_seconds(1, lambda: view.get_uri() == uri and view.load_uri(uri))
    return True


def hotkey_opens():
    """'overlay' or 'window', from the backend's preferences; overlay when it can't be reached."""
    try:
        with urllib.request.urlopen(URL + 'api/preferences', timeout=0.5) as r:
            return json.load(r).get('hotkeyOpens', 'overlay')
    except Exception:
        return 'overlay'


def web_view():
    view = WebKit2.WebView()
    view.connect('load-failed', retry)
    return view


class Sonar(Gtk.Application):
    def __init__(self):
        super().__init__(application_id='dev.edo.sonar', flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE)
        self.main = self.overlay = self.overlay_view = self.obs = None

    def do_command_line(self, cmdline):
        # a second launch doesn't start a new process: its arguments arrive here, in the running one
        if '--toggle' in cmdline.get_arguments()[1:]:
            if hotkey_opens() == 'window':
                self.toggle_main()
            else:
                self.toggle_overlay()
        else:
            self.show_main()
        return 0

    def show_main(self):
        if not self.main:
            self.main = Gtk.ApplicationWindow(application=self, title='Sonar')
            self.main.set_default_size(900, 600)
            view = web_view()
            view.connect('create', self.open_obs)
            view.load_uri(URL)
            self.main.add(view)
            self.main.connect('destroy', lambda *_: setattr(self, 'main', None))
            self.main.show_all()
        self.main.present()

    def open_obs(self, view, _action):
        """window.open from the mixer (its OBS button): one OBS window, reused."""
        if self.obs:
            self.obs.present()
            return None  # cancels creating another view
        # the new view must be related to the opener, or WebKit won't hand it the popup
        child = WebKit2.WebView.new_with_related_view(view)
        child.connect('load-failed', retry)
        win = Gtk.Window(application=self, title='Sonar — OBS')
        win.set_default_size(620, 560)
        win.add(child)
        child.connect('ready-to-show', lambda *_: win.show_all())
        child.connect('close', lambda *_: win.destroy())  # window.close()
        win.connect('destroy', lambda *_: setattr(self, 'obs', None))
        self.obs = win
        return child

    def toggle_main(self):
        if self.main and self.main.is_active():
            self.main.close()
        else:
            self.show_main()

    def toggle_overlay(self):
        if not self.overlay:
            self.make_overlay()
        if self.overlay.get_visible():
            self.hide_overlay()
        else:
            # a fresh view (and WebKit page process) each time: ~130 ms to the mixer with data
            self.overlay_view = self.make_overlay_view()
            self.overlay.add(self.overlay_view)
            self.overlay_view.load_uri(OVERLAY_URL)
            self.overlay.show_all()

    def hide_overlay(self, *_):
        self.overlay.hide()
        # destroy the page, not just blank it: stops its polling (level meters, the mic) and ends its
        # WebKit page process, ~100 MiB that would otherwise sit idle while the overlay is closed
        if self.overlay_view:
            self.overlay.remove(self.overlay_view)
            self.overlay_view.destroy()
            self.overlay_view = None

    def make_overlay(self):
        # the hidden overlay window keeps this process alive (no Python/GTK cold start on the next toggle);
        # its page is created on show and destroyed on hide
        win = Gtk.Window(application=self, title='Sonar overlay')
        if GtkLayerShell.is_supported():
            # layer-shell (KDE, wlroots compositors): a layer above everything, fullscreen games included
            GtkLayerShell.init_for_window(win)
            GtkLayerShell.set_namespace(win, 'sonar')
            GtkLayerShell.set_layer(win, GtkLayerShell.Layer.OVERLAY)
            for edge in (GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.BOTTOM,
                         GtkLayerShell.Edge.LEFT, GtkLayerShell.Edge.RIGHT):
                GtkLayerShell.set_anchor(win, edge, True)
            GtkLayerShell.set_exclusive_zone(win, -1)  # cover panels too
            # never take keyboard focus: fullscreen games (SDL, Wine/Proton) minimize when they lose it.
            # The mouse still works (pointer focus moves to the overlay, which also breaks a game's
            # pointer lock); close with the hotkey or a backdrop click.
            # ponytail: no keyboard in the overlay (no Esc, no typing): use the window to add/rename/save
            GtkLayerShell.set_keyboard_mode(win, GtkLayerShell.KeyboardMode.NONE)
        else:
            # e.g. GNOME: no layer-shell, best effort
            win.fullscreen()
            win.set_keep_above(True)
        win.set_visual(win.get_screen().get_rgba_visual())
        win.set_app_paintable(True)
        self.overlay = win

    def make_overlay_view(self):
        view = web_view()
        view.set_background_color(Gdk.RGBA(0, 0, 0, 0))
        # the page posts 'close' when the dimmed backdrop is clicked
        manager = view.get_user_content_manager()
        manager.connect('script-message-received::sonar', self.hide_overlay)
        manager.register_script_message_handler('sonar')
        return view


GLib.set_prgname('sonar')
# native popups (dropdown lists) use the GTK theme, not page CSS; Breeze ignores prefer-dark, so force the dark variant
Gtk.Settings.get_default().set_property('gtk-theme-name', 'Breeze-Dark')
Gtk.Window.set_default_icon_name('sonar')
Sonar().run(sys.argv)
