"""Opt-in, verified Android payload transfers (no automatic retry).

Pacing bounds host pipe admission, not USB packets or adb's internal buffers.
Only the enabled path requires POSIX pipe selectors and advisory file locks.
"""
import contextlib
import hashlib
import logging
import os
import selectors
import shlex
import stat
import subprocess
import tempfile
import threading
import time
import uuid

from . import adb_cmds

log = logging.getLogger("encapp.adb")
RATE_BYTES = 2 * 1024 * 1024
BLOCK_BYTES = 4096
IO_TIMEOUT = 30
CONTROL_TIMEOUT = 30
BULK_MIN_TIMEOUT = 120
BULK_BYTES_PER_SECOND = 4 * 1024 * 1024
ABSENT_MARKER = "ENCAPP_FILE_ABSENT"


def bulk_timeout(size):
    """Allow slow on-device hash/assembly work without an unbounded wait."""
    return max(BULK_MIN_TIMEOUT, 60 + size / BULK_BYTES_PER_SECOND)


class PayloadTransferError(RuntimeError):
    """An Android payload failed; its partial is retained for diagnosis."""


def _remote_command(inner, path):
    if adb_cmds._is_app_private_path(path):
        return "run-as com.facebook.encapp sh -c " + shlex.quote(inner)
    return "sh -c " + shlex.quote(inner)


def shell_command(serial, inner, path):
    return (f"adb -s {shlex.quote(serial)} shell "
            + shlex.quote(_remote_command(inner, path)))


def control(command, timeout=None, debug=0):
    """Bound every new control client; kill/reap only that owned subprocess."""
    timeout = CONTROL_TIMEOUT if timeout is None else timeout
    argv = shlex.split(command) if isinstance(command, str) else command
    if debug:
        log.debug("payload control timeout=%s argv=%r", timeout, argv)
    process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        try:
            output, error = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            output, error = process.communicate(timeout=IO_TIMEOUT)
            raise PayloadTransferError(
                f"control timed out after {timeout}s: {error.decode(errors='replace').strip()}; "
                "remote outcome unknown, no retry")
        return (process.returncode == 0, output.decode(errors="replace"),
                error.decode(errors="replace"))
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=IO_TIMEOUT)
        process.stdout.close()
        process.stderr.close()


def shell(serial, inner, path, debug=0, portable=False, timeout=None):
    # All new controls avoid host shells and legacy unbounded run_cmd.
    # Keep portable as a compatibility keyword; both paths are now identical.
    return control(["adb", "-s", serial, "shell", _remote_command(inner, path)],
                   timeout=timeout, debug=debug)


def _path(path):
    return shlex.quote(adb_cmds._app_relative_path(path)
                       if adb_cmds._is_app_private_path(path) else path)


def remote_size(serial, path, debug=0, portable=False):
    parent = _path(os.path.dirname(path) or ".")
    basename = shlex.quote(os.path.basename(path))
    # A failed stat is NOT an absent file. Confirm that the parent can be
    # searched/listed and that no directory entry names this file before
    # emitting the sole normal-miss marker. Permission/run-as/transport and
    # malformed-stat errors stay errors, including dangling symlinks.
    inner = (
        f"p={_path(path)}; d={parent}; "
        'if [ -e "$p" ] || [ -L "$p" ]; then stat -L -c %s -- "$p"; '
        'elif [ -d "$d" ] && [ -x "$d" ] && [ -r "$d" ]; then '
        'ls -a -- "$d" >/dev/null || exit 1; '
        'for entry in "$d"/* "$d"/.[!.]* "$d"/..?*; do '
        f'if [ "${{entry##*/}}" = {basename} ]; then '
        'echo "file entry exists but cannot be statted" >&2; exit 1; fi; done; '
        f"printf '%s\\n' {ABSENT_MARKER}; "
        'else echo "cannot inspect source parent directory" >&2; exit 1; fi'
    )
    ok, output, error = shell(serial, inner, path, debug, portable=portable)
    if not ok:
        raise PayloadTransferError(f"file size probe failed for {path}: {error.strip()}")
    if output.strip() == ABSENT_MARKER:
        return None
    value = output.strip()
    if value and all(character in "0123456789" for character in value):
        return int(value)
    raise PayloadTransferError(f"malformed file size for {path}: {output!r}; {error.strip()}")


def remote_hash(serial, path, debug=0, portable=False, size=None):
    size = remote_size(serial, path, debug, portable=portable) if size is None else size
    if size is None:
        return None
    ok, output, error = shell(serial, f"md5sum -- {_path(path)}", path, debug,
                              portable=portable, timeout=bulk_timeout(size))
    digest = output.split()[0] if ok and output.split() else ""
    if not ok or len(digest) != 32 or any(c not in "0123456789abcdef" for c in digest):
        raise PayloadTransferError(
            f"file hash probe failed for {path}: {error.strip()}; {output!r}")
    return digest


def publish(serial, partial, destination, size, digest, debug=0):
    """Require no-directory-target rename and verify the exact published file."""
    target = _path(destination)
    inner = (f"if [ -d {target} ]; then echo 'destination is a directory' >&2; exit 1; fi; "
             f"mv -fT -- {_path(partial)} {target}")
    ok, _, error = shell(serial, inner, partial, debug)
    if not ok:
        raise PayloadTransferError(
            f"no-directory-target rename failed (mv -T required, no fallback): {error.strip()}")
    ok, _, error = shell(serial, f"test -f {target} && test ! -L {target}", destination, debug)
    if (not ok or remote_size(serial, destination, debug) != size
            or remote_hash(serial, destination, debug, size=size) != digest):
        raise PayloadTransferError(
            f"published destination verification failed: {destination}; {error.strip()}")


@contextlib.contextmanager
def _exclusive_lock(key):
    if os.name != "posix":
        raise PayloadTransferError("paced Android transfers require a POSIX host")
    import fcntl

    path = os.path.join(tempfile.gettempdir(), f"encapp-{key}-{os.getuid()}.lock")
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "a+b") as lock:
        info = os.fstat(lock.fileno())
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) & 0o077):
            raise PayloadTransferError("unsafe payload lock file")
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


@contextlib.contextmanager
def payload_budget(paced):
    """Serialize enabled transfers for this OS user, across devices/processes.

    Every admission sleeps for its own byte interval; idle time never earns
    burst credit. There is no Linux-specific pipe resizing or global import
    of fcntl, so ordinary transfers and iOS retain their existing behavior.
    """
    if not paced:
        yield lambda count: None
        return
    with _exclusive_lock("payload"):
        yield lambda count: time.sleep(count / RATE_BYTES)


def split_budget(serial, directory, digest, split_size):
    key = hashlib.sha256(f"{serial}\0{directory}\0{digest}\0{split_size}".encode()).hexdigest()
    return _exclusive_lock("split-" + key)


class _StderrTail:
    def __init__(self, stream):
        self.stream = stream
        self.tail = bytearray()
        self.thread = threading.Thread(target=self._drain, daemon=True)
        self.thread.start()

    def _drain(self):
        while True:
            chunk = self.stream.read(BLOCK_BYTES)
            if not chunk:
                break
            self.tail.extend(chunk)
            del self.tail[:-65536]

    def finish(self):
        self.thread.join(IO_TIMEOUT)
        if self.thread.is_alive():
            raise PayloadTransferError("adb stderr did not close")
        return bytes(self.tail).decode(errors="replace")


def _ready(selector):
    if not selector.select(IO_TIMEOUT):
        raise PayloadTransferError("adb payload inactive for 30 seconds")


def _copy_pipe(stream, local, size, upload, admit):
    """Bound each nonblocking pipe operation and reject short/extra payloads."""
    os.set_blocking(stream.fileno(), False)
    event = selectors.EVENT_WRITE if upload else selectors.EVENT_READ
    with selectors.DefaultSelector() as selector:
        selector.register(stream, event)
        remaining = size
        while remaining:
            count = min(BLOCK_BYTES, remaining)
            admit(count)
            _ready(selector)
            if upload:
                data = local.read(count)
                if len(data) != count:
                    raise PayloadTransferError("source ended before observed size")
                offset = 0
                while offset < len(data):
                    _ready(selector)
                    accepted = os.write(stream.fileno(), data[offset:])
                    if not accepted:
                        raise PayloadTransferError("zero-byte adb write")
                    offset += accepted
                remaining -= len(data)
            else:
                data = os.read(stream.fileno(), count)
                if not data:
                    raise PayloadTransferError("adb ended before observed size")
                if local.write(data) != len(data):
                    raise PayloadTransferError("short local payload write")
                remaining -= len(data)
        if upload:
            if local.read(1):
                raise PayloadTransferError("source grew beyond observed size")
        else:
            _ready(selector)
            if os.read(stream.fileno(), 1):
                raise PayloadTransferError("adb exceeded observed size")


def _stream(serial, path, inner, local, size, upload, admit, direct_download=False):
    process = subprocess.Popen(
        ["adb", "-s", serial, "exec-in" if upload else "exec-out",
         _remote_command(inner, path)],
        stdin=subprocess.PIPE if upload else subprocess.DEVNULL,
        stdout=(subprocess.DEVNULL if upload else
                local if direct_download else subprocess.PIPE),
        stderr=subprocess.PIPE, bufsize=0,
    )
    tail = _StderrTail(process.stderr)
    stream = process.stdin if upload else process.stdout
    try:
        if not direct_download:
            _copy_pipe(stream, local, size, upload, admit)
        if upload:
            stream.close()
        code = process.wait(timeout=IO_TIMEOUT)
        error = tail.finish()
        if code:
            raise PayloadTransferError(f"adb exited {code}: {error}")
    finally:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=IO_TIMEOUT)
        if stream is not None:
            stream.close()
        tail.finish()
        process.stderr.close()


def push(serial, source, destination, debug=0):
    """Publish only size/hash-verified remote bytes; retain failures, no retry."""
    partial = destination + ".partial." + uuid.uuid4().hex
    try:
        with payload_budget(True) as admit:
            size = os.path.getsize(source)
            digest = adb_cmds.get_host_hash(source, debug)
            inner = f"set -C; cat > {_path(partial)}"
            with open(source, "rb") as local:
                _stream(serial, partial, inner, local, size, True, admit)
            if (remote_size(serial, partial, debug) != size
                    or remote_hash(serial, partial, debug, size=size) != digest):
                raise PayloadTransferError("remote partial size/hash mismatch")
            publish(serial, partial, destination, size, digest, debug)
        return True
    except (OSError, ValueError, subprocess.SubprocessError,
            PayloadTransferError) as error:
        log.error("payload upload failed: %s; remote partial: %s", error, partial)
        return False


def pull(serial, source, destination, debug=0, verify_hash=True, paced=True,
         snapshot=False, raise_errors=False):
    """Publish a strict immutable download or an observed-length prefix.

    Snapshots of append-only control files cannot claim whole-file hashes.
    An unpaced snapshot uses a bounded remote producer and direct file output
    instead of POSIX pipe selectors. Every mode still checks exact size/exit.
    """
    if snapshot and verify_hash:
        raise ValueError("a growing-file snapshot cannot verify a whole-file hash")
    partial_path = None
    try:
        with payload_budget(paced) as admit:
            size = remote_size(serial, source, debug, portable=not paced)
            if size is None:
                return False
            digest = (remote_hash(serial, source, debug, portable=not paced, size=size)
                      if verify_hash else None)
            if verify_hash and digest is None:
                raise PayloadTransferError("cannot obtain remote hash")
            parent = os.path.dirname(os.path.abspath(destination))
            os.makedirs(parent, exist_ok=True)
            with tempfile.NamedTemporaryFile(
                    prefix=os.path.basename(destination) + ".partial.",
                    dir=parent, delete=False) as local:
                partial_path = local.name
                inner = (f"dd if={_path(source)} bs=1 count={size} 2>/dev/null"
                         if snapshot else f"cat -- {_path(source)}")
                _stream(serial, source, inner, local, size, False, admit,
                        direct_download=not paced)
                local.flush()
                os.fsync(local.fileno())
            if (os.path.getsize(partial_path) != size or
                    (verify_hash and
                     adb_cmds.get_host_hash(partial_path, debug) != digest)):
                raise PayloadTransferError("download size/hash mismatch")
            os.replace(partial_path, destination)
        return True
    except (OSError, ValueError, subprocess.SubprocessError,
            PayloadTransferError) as error:
        log.error("payload download failed: %s; local partial: %s", error, partial_path)
        if raise_errors:
            raise PayloadTransferError(f"{error}; local partial: {partial_path}") from error
        return False


def file_commands(serial, paths, operation, destination=None, max_bytes=8192):
    """Yield ordered cat/rm commands bounded including host-shell quoting."""
    if operation not in ("cat", "rm") or not paths:
        raise ValueError("nonempty cat/rm file list required")
    if operation == "cat" and not destination:
        raise ValueError("cat requires an assembly partial")
    context = destination or paths[0]
    suffix = f" >> {_path(destination)}" if operation == "cat" else ""
    prefix = "cat -- " if operation == "cat" else "rm -f -- "
    batch = []
    for path in paths:
        item = _path(path)
        proposed = shell_command(serial, prefix + " ".join(batch + [item]) + suffix,
                                 context)
        if len(proposed.encode()) > max_bytes:
            if not batch:
                raise ValueError("file path exceeds shell command budget")
            yield shell_command(serial, prefix + " ".join(batch) + suffix, context)
            batch = []
            if len(shell_command(serial, prefix + item + suffix, context).encode()) > max_bytes:
                raise ValueError("file path exceeds shell command budget")
        batch.append(item)
    if batch:
        yield shell_command(serial, prefix + " ".join(batch) + suffix, context)
