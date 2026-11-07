import json
from pathlib import Path
import tempfile
import unittest
import wave
from audit_references import audit


class DraftAuditTest(unittest.TestCase):
    def test_overlap_is_flagged_for_listening_not_declared_a_model_error(self):
        with tempfile.TemporaryDirectory() as location:
            root = Path(location)
            audio = root / 'example.wav'
            with wave.open(str(audio), 'wb') as stream:
                stream.setparams((1, 2, 16000, 0, 'NONE', ''))
                stream.writeframes(b'\0\0' * 16000)
            draft = root / 'example.draft.json'
            draft.write_text(json.dumps({'transcript': [
                dict(speaker_id='speaker_1', start_ms=0, end_ms=700, text='Да, конечно.'),
                dict(speaker_id='speaker_2', start_ms=500, end_ms=1000, text='Согласен.')]}))
            result = audit(audio, draft)
            self.assertEqual(1000, result['audio_ms'])
            self.assertFalse(result['manually_verified'])
            self.assertEqual('overlap_requires_listening', result['issues'][0]['kind'])
            self.assertEqual(200, result['issues'][0]['overlap_ms'])
            draft.write_text(json.dumps({'transcript': [
                dict(speaker_id='speaker_1', start_ms=0, end_ms=1001, text='Да. 2')]}))
            # A rounding tolerance of 1ms is permitted; a genuine overrun is not.
            self.assertNotIn('invalid_interval', [x['kind'] for x in audit(audio,draft)['issues']])
            draft.write_text(draft.read_text().replace('1001','1100'))
            self.assertIn('invalid_interval', [x['kind'] for x in audit(audio,draft)['issues']])


if __name__ == '__main__':
    unittest.main()
