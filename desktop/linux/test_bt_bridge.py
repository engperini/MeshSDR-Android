"""Tests the byte bridge of meshsdr-bt-bridge.py without Bluetooth: a socket pair
stands in for the phone's RFCOMM connection, a local TCP server for MeshSDR."""
import importlib.util
import os
import socket
import threading
import unittest

spec = importlib.util.spec_from_file_location(
    "bt_bridge", os.path.join(os.path.dirname(os.path.abspath(__file__)), "meshsdr-bt-bridge.py"))
bt_bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bt_bridge)


def read_line(sock):
    data = b""
    while not data.endswith(b"\n"):
        chunk = sock.recv(4096)
        if not chunk:
            break
        data += chunk
    return data


class BridgeTest(unittest.TestCase):
    def test_passes_lines_both_ways_and_closes(self):
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        received = []

        def fake_meshsdr():
            conn, _ = listener.accept()
            conn.sendall(b'{"t":"hello"}\n')
            received.append(read_line(conn))
            conn.sendall(b'{"t":"msg","line":"x"}\n' * 1000)
            conn.close()

        server = threading.Thread(target=fake_meshsdr)
        server.start()
        phone, bridge_end = socket.socketpair()
        worker = threading.Thread(target=bt_bridge.bridge, args=(bridge_end, listener.getsockname()))
        worker.start()
        self.assertEqual(read_line(phone), b'{"t":"hello"}\n')
        phone.sendall(b'{"t":"cmd","op":"connect"}\n')
        rest = b""
        while True:
            chunk = phone.recv(65536)
            if not chunk:
                break
            rest += chunk
        server.join(5)
        worker.join(5)
        self.assertEqual(received, [b'{"t":"cmd","op":"connect"}\n'])
        self.assertEqual(rest, b'{"t":"msg","line":"x"}\n' * 1000)
        self.assertFalse(worker.is_alive())
        listener.close()

    def test_tells_the_phone_when_meshsdr_is_not_running(self):
        unused = socket.socket()
        unused.bind(("127.0.0.1", 0))
        address = unused.getsockname()
        unused.close()
        phone, bridge_end = socket.socketpair()
        bt_bridge.bridge(bridge_end, address)
        self.assertIn(b"not running", read_line(phone))


if __name__ == "__main__":
    unittest.main()
