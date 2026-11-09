#!/usr/bin/env python3
"""Authored metadata/download checks; no network or package extraction."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('audio_test_deps', ROOT / 'scripts/linux-audio-test-deps.py')
AUDIO = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIO)


class LinuxAudioTestDependencies(unittest.TestCase):
    def setUp(self):
        (ROOT / '.work/tmp').mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=ROOT / '.work/tmp')
        self.addCleanup(self.temp.cleanup)
        self.destination = Path(self.temp.name) / 'authored.deb'
        self.data = b'authored archive bytes'
        self.fields = {'Package': 'libpulse0', 'Version': '1:16.1-authored', 'Architecture': 'amd64',
                       'Filename': 'pool/main/p/pulseaudio/libpulse0_authored_amd64.deb',
                       'Size': str(len(self.data)), 'SHA256': hashlib.sha256(self.data).hexdigest()}

    def test_committed_inventory_is_exact_and_every_record_is_valid(self):
        pin = json.loads((ROOT / 'scripts/linux-audio-test-deps.json').read_text())
        self.assertEqual(1, pin['schema'])
        self.assertEqual(set(AUDIO.PACKAGES), {r['Package'] for r in pin['packages']})
        self.assertEqual(len(AUDIO.PACKAGES), len(pin['packages']))
        for record in pin['packages']:
            fields = AUDIO.metadata(record['Package'], '\n'.join(f'{k}: {v}' for k,v in record.items()))
            self.assertEqual(record, fields)

    def test_exact_architecture_path_hash_and_size_metadata(self):
        output = '\n'.join(f'{k}: {v}' for k,v in self.fields.items()) + '\n Description continuation'
        self.assertEqual(self.fields, AUDIO.metadata('libpulse0', output))
        for key,bad in [('Filename','pool/../escape.deb'),('Filename','https://example.invalid/p.deb'),
                        ('Size','9999999999999'),('SHA256','bad'),('Architecture','arm64')]:
            fields = dict(self.fields, **{key:bad})
            with self.subTest(key=key), self.assertRaises(ValueError):
                AUDIO.metadata('libpulse0', '\n'.join(f'{k}: {v}' for k,v in fields.items()))

    def response(self, data):
        value = io.BytesIO(data)
        value.status = 200
        value.url = 'https://archive.ubuntu.com/ubuntu/' + self.fields['Filename']
        return value

    def test_download_requires_exact_size_and_digest_before_writing(self):
        with patch.object(AUDIO.urllib.request, 'urlopen', return_value=self.response(self.data)) as request:
            AUDIO.fetch(self.fields, self.destination)
        self.assertEqual(self.data, self.destination.read_bytes())
        self.assertEqual(60, request.call_args.kwargs['timeout'])
        self.destination.unlink()
        for data in (b'wrong', self.data + b'extra'):
            with patch.object(AUDIO.urllib.request, 'urlopen', return_value=self.response(data)), self.assertRaises(ValueError):
                AUDIO.fetch(self.fields, self.destination)
            self.assertFalse(self.destination.exists())

    def test_redirect_to_another_host_is_rejected(self):
        response = self.response(self.data)
        response.url = 'https://example.invalid/package.deb'
        with patch.object(AUDIO.urllib.request, 'urlopen', return_value=response), self.assertRaises(ValueError):
            AUDIO.fetch(self.fields, self.destination)
        self.assertFalse(self.destination.exists())

    def test_existing_library_needs_no_download_or_subprocess(self):
        with patch.object(AUDIO.ctypes, 'CDLL') as loader, patch.object(AUDIO.subprocess, 'check_output') as command:
            self.assertEqual('', AUDIO.prepare())
        self.assertEqual(list(AUDIO.LIBRARIES), [call.args[0] for call in loader.call_args_list])
        command.assert_not_called()

    def test_missing_egl_is_detected_even_when_pulseaudio_loads(self):
        def load(library):
            if library == 'libEGL.so.1':
                raise OSError('authored missing EGL')
        with patch.object(AUDIO, 'LIBRARIES', ('libpulse.so.0', 'libEGL.so.1')), \
                patch.object(AUDIO.ctypes, 'CDLL', side_effect=load) as loader, \
                patch.object(AUDIO.Path, 'read_text', return_value='ID=authored\n'), \
                patch.object(AUDIO.subprocess, 'check_output') as command, self.assertRaises(SystemExit):
            AUDIO.prepare()
        self.assertEqual(['libpulse.so.0', 'libEGL.so.1'], [call.args[0] for call in loader.call_args_list])
        command.assert_not_called()

    def test_missing_library_on_another_distribution_is_rejected(self):
        with patch.object(AUDIO.ctypes, 'CDLL', side_effect=OSError), \
                patch.object(AUDIO.Path, 'read_text', return_value='ID=authored\nVERSION_ID="24.04"\n'), \
                patch.object(AUDIO.subprocess, 'check_output') as command, self.assertRaises(SystemExit):
            AUDIO.prepare()
        command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
