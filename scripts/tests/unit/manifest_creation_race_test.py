"""Single-purpose no-device regression for the manifest creation race."""
from pathlib import Path
import shlex
import sys
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from encapp_tool import adb_payload  # noqa: E402
from .fake_adb import FakeAdbTest  # noqa: E402


class ManifestCreationRaceTest(FakeAdbTest):
    def test_creation_after_initial_check_is_read_in_both_modes(self):
        data = b'{"event":"session_start","session_id":"S1"}\n'
        seed = self.root / "created-manifest.bytes"
        seed.write_bytes(data)
        original = adb_payload.shell
        gate = 'elif [ -d "$d" ]'

        def create_after_initial_check(serial, inner, path, debug=0, **kwargs):
            if gate in inner:
                # The elif condition executes only AFTER the initial -e/-L
                # checks found no file, but before parent/entry enumeration.
                self.assertEqual(1, inner.count(gate))
                replacement = (f'elif {{ cat -- {shlex.quote(str(seed))} > "$p"; '
                               '[ -d "$d" ]; }')
                inner = inner.replace(gate, replacement, 1)
            return original(serial, inner, path, debug, **kwargs)

        for paced in (True, False):
            self.source.unlink()
            with self.subTest(paced=paced), mock.patch.object(
                    adb_payload, "shell", side_effect=create_after_initial_check):
                self.assertTrue(adb_payload.pull(
                    "Device A", str(self.source), str(self.destination),
                    verify_hash=False, snapshot=True, paced=paced))
                self.assertEqual(data, self.destination.read_bytes())
