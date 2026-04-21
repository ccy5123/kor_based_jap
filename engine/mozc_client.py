"""Mozc IPC conversion engine.

Communicates with mozc_server via Unix domain socket using the Mozc
protobuf IPC protocol (4-byte big-endian length header + proto bytes).

Requires:
  sudo apt install mozc-server emacs-mozc-bin
  pip install protobuf

Protocol reference:
  https://github.com/google/mozc/blob/master/src/protocol/commands.proto
  https://github.com/google/mozc/blob/master/src/ipc/ipc.h
"""

from __future__ import annotations
import os
import socket
import struct
import subprocess
import tempfile
import time
from pathlib import Path

from .base import ConversionEngine
from .candidate import Candidate, ConversionResult

MOZC_SERVER_BIN = "/usr/lib/mozc/mozc_server"
MOZC_SOCKET_DIR = Path(tempfile.gettempdir()) / f"mozc.{os.getuid()}"


def _find_mozc_server() -> str | None:
    for path in [
        "/usr/lib/mozc/mozc_server",
        "/usr/lib/mozc_server",
        "/usr/bin/mozc_server",
    ]:
        if os.path.isfile(path):
            return path
    result = subprocess.run(["which", "mozc_server"], capture_output=True, text=True)
    return result.stdout.strip() or None


class MozcConversionEngine(ConversionEngine):
    """Conversion engine backed by mozc_server IPC.

    Session lifecycle:
        engine = MozcConversionEngine()
        if engine.is_available():
            result = engine.convert("きみを")
        engine.close()
    """

    def __init__(self) -> None:
        self._proc: subprocess.Popen | None = None
        self._sock: socket.socket | None = None
        self._session_id: int | None = None
        self._ready = False

    def is_available(self) -> bool:
        return _find_mozc_server() is not None

    def _ensure_connection(self) -> bool:
        if self._ready:
            return True
        try:
            return self._connect()
        except Exception as e:
            print(f"[MozcClient] connection failed: {e}")
            return False

    def _connect(self) -> bool:
        server_bin = _find_mozc_server()
        if not server_bin:
            return False

        # Start server if not running
        socket_path = MOZC_SOCKET_DIR / "session"
        if not socket_path.exists():
            self._proc = subprocess.Popen(
                [server_bin, "--logtostderr=false"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            # Wait for socket
            for _ in range(20):
                if socket_path.exists():
                    break
                time.sleep(0.2)

        if not socket_path.exists():
            return False

        self._sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self._sock.connect(str(socket_path))
        self._session_id = self._create_session()
        self._ready = self._session_id is not None
        return self._ready

    def _send_command(self, command_bytes: bytes) -> bytes:
        """Send a raw protobuf command and return the response bytes."""
        assert self._sock is not None
        length = struct.pack(">I", len(command_bytes))
        self._sock.sendall(length + command_bytes)
        resp_len_bytes = self._sock.recv(4)
        if len(resp_len_bytes) < 4:
            return b""
        resp_len = struct.unpack(">I", resp_len_bytes)[0]
        resp = b""
        while len(resp) < resp_len:
            chunk = self._sock.recv(resp_len - len(resp))
            if not chunk:
                break
            resp += chunk
        return resp

    def _create_session(self) -> int | None:
        """Create a Mozc session and return session_id."""
        try:
            from mozc_proto import commands_pb2
            req = commands_pb2.Input()
            req.type = commands_pb2.Input.CREATE_SESSION
            resp_bytes = self._send_command(req.SerializeToString())
            resp = commands_pb2.Output()
            resp.ParseFromString(resp_bytes)
            return resp.id if resp.HasField("id") else None
        except ImportError:
            # protobuf / mozc_proto not available; use emacs_helper fallback
            return self._create_session_via_helper()

    def _create_session_via_helper(self) -> int | None:
        """Alternative: use mozc_emacs_helper for session (no protobuf needed)."""
        helper = "/usr/bin/mozc_emacs_helper"
        if not os.path.isfile(helper):
            return None
        # mozc_emacs_helper uses a line-oriented S-expression protocol
        self._helper_proc = subprocess.Popen(
            [helper],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
        self._helper_proc.stdin.write("(mozc-CreateSession)\n")
        self._helper_proc.stdin.flush()
        line = self._helper_proc.stdout.readline()
        # line: (CreateSession ((id . 123) ...))
        import re
        m = re.search(r'\(id\s*\.\s*(\d+)\)', line)
        if m:
            self._ready = True
            self._use_helper = True
            return int(m.group(1))
        return None

    def _convert_via_helper(self, session_id: int, kana: str) -> list[tuple[str, str]]:
        """Send kana to emacs_helper and return [(surface, reading)] candidates."""
        import re
        p = self._helper_proc
        # Send each kana character as a KeyEvent
        for ch in kana:
            p.stdin.write(f'(mozc-SendKey {session_id} "{ch}")\n')
            p.stdin.flush()
            p.stdout.readline()  # consume ack

        # Request conversion
        p.stdin.write(f"(mozc-Convert {session_id})\n")
        p.stdin.flush()
        response = p.stdout.readline()

        # Parse candidates from S-expression response
        candidates: list[tuple[str, str]] = []
        for m in re.finditer(r'\(value\s+"([^"]+)"\)', response):
            candidates.append((m.group(1), kana))
        return candidates

    def convert(self, kana: str) -> ConversionResult:
        if not self._ensure_connection() or self._session_id is None:
            return ConversionResult(input_kana=kana, candidates=[], engine="mozc(unavailable)")

        try:
            if getattr(self, "_use_helper", False):
                pairs = self._convert_via_helper(self._session_id, kana)
            else:
                pairs = self._convert_via_ipc(self._session_id, kana)

            candidates = [
                Candidate(surface=s, reading=r, score=1.0 - i * 0.05, source="mozc")
                for i, (s, r) in enumerate(pairs)
            ]
            if not any(c.surface == kana for c in candidates):
                candidates.append(Candidate(surface=kana, reading=kana, score=0.0, source="mozc"))
            return ConversionResult(input_kana=kana, candidates=candidates, engine="mozc")
        except Exception as e:
            return ConversionResult(input_kana=kana, candidates=[], engine=f"mozc(error:{e})")

    def _convert_via_ipc(self, session_id: int, kana: str) -> list[tuple[str, str]]:
        """Protobuf-based conversion via mozc_server socket."""
        from mozc_proto import commands_pb2
        req = commands_pb2.Input()
        req.id = session_id
        req.type = commands_pb2.Input.SEND_COMMAND
        req.command.type = commands_pb2.SessionCommand.CONVERT
        for ch in kana:
            key_req = commands_pb2.Input()
            key_req.id = session_id
            key_req.type = commands_pb2.Input.SEND_KEY
            key_req.key.key_string = ch
            self._send_command(key_req.SerializeToString())
        resp_bytes = self._send_command(req.SerializeToString())
        resp = commands_pb2.Output()
        resp.ParseFromString(resp_bytes)
        pairs = []
        for seg in resp.output.preedit.segment:
            for cand in seg.candidates:
                pairs.append((cand.value, kana))
        return pairs

    def close(self) -> None:
        if getattr(self, "_helper_proc", None):
            self._helper_proc.stdin.write("(mozc-DeleteSession)\n")
            self._helper_proc.stdin.flush()
            self._helper_proc.terminate()
        if self._sock:
            self._sock.close()
        if self._proc:
            self._proc.terminate()
        self._ready = False
