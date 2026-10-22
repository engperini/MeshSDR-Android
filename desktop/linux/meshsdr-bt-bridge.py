#!/usr/bin/env python3
"""MeshSDR Bluetooth bridge for a Raspberry Pi (or any Linux with BlueZ).

Registers a Bluetooth RFCOMM service with BlueZ and connects each paired
phone that opens it to the MeshSDR server (MeshSDR --server, TCP port 14424
on this machine). The bytes are passed through unchanged: the phone speaks the
same line protocol over Bluetooth as over Wi-Fi.

Needs python3-dbus and python3-gi (preinstalled on Raspberry Pi OS desktop).
Run by meshsdr-bluetooth.service; pair the phone once with the Pi first.
"""
import socket
import sys
import threading

SERVICE_UUID = "5e1f6b1c-6d2a-4c38-9b1e-4d6573685344"  # RemoteLink.BLUETOOTH_UUID in the app
RFCOMM_CHANNEL = 22
LINK = ("127.0.0.1", 14424)  # RemoteLink.PORT
PROFILE_PATH = "/org/meshsdr/link"


def log(text):
    print(text, flush=True)


def pump(src, dst):
    """Copies src to dst until either side closes, then shuts both down."""
    try:
        while True:
            data = src.recv(4096)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def bridge(phone, link=LINK):
    """Joins one phone connection to the MeshSDR server; returns when either ends."""
    try:
        server = socket.create_connection(link, timeout=5)
    except OSError as e:
        log(f"MeshSDR server not reachable at {link[0]}:{link[1]}: {e}")
        try:
            phone.sendall(b'{"t":"error","text":"MeshSDR server is not running on the Pi '
                          b'(sudo systemctl start meshsdr-server@$USER)"}\n')
        except OSError:
            pass
        phone.close()
        return
    server.settimeout(None)
    back = threading.Thread(target=pump, args=(server, phone), daemon=True)
    back.start()
    pump(phone, server)
    back.join()
    phone.close()
    server.close()


def create_profile(bus, link=LINK):
    """The BlueZ Profile1 object: BlueZ hands it each phone connection as a file descriptor."""
    import dbus.service

    class Profile(dbus.service.Object):
        @dbus.service.method("org.bluez.Profile1", in_signature="oha{sv}", out_signature="")
        def NewConnection(self, device, fd, properties):
            sock = socket.socket(fileno=fd.take())
            sock.setblocking(True)
            log(f"Phone connected: {device}")
            threading.Thread(target=bridge, args=(sock, link), daemon=True).start()

        @dbus.service.method("org.bluez.Profile1", in_signature="o", out_signature="")
        def RequestDisconnection(self, device):
            log(f"Phone disconnected: {device}")

        @dbus.service.method("org.bluez.Profile1", in_signature="", out_signature="")
        def Release(self):
            log("BlueZ released the profile")

    return Profile(bus, PROFILE_PATH)


def main():
    try:
        import dbus
        import dbus.mainloop.glib
        from gi.repository import GLib
    except ImportError:
        log("Missing python3-dbus / python3-gi: sudo apt install python3-dbus python3-gi")
        return 1

    dbus.mainloop.glib.DBusGMainLoop(set_as_default=True)
    bus = dbus.SystemBus()
    create_profile(bus)
    manager = dbus.Interface(bus.get_object("org.bluez", "/org/bluez"), "org.bluez.ProfileManager1")
    manager.RegisterProfile(PROFILE_PATH, SERVICE_UUID, {
        "Name": "MeshSDR",
        "Role": "server",
        "Channel": dbus.UInt16(RFCOMM_CHANNEL),
        # Only phones paired with the Pi can connect.
        "RequireAuthentication": True,
        "RequireAuthorization": False,
    })
    log(f"MeshSDR Bluetooth bridge ready (RFCOMM channel {RFCOMM_CHANNEL}) -> {LINK[0]}:{LINK[1]}")
    GLib.MainLoop().run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
