import json
from pathlib import Path
import tempfile
import unittest
import evaluate


class EvaluationTest(unittest.TestCase):
    def test_known_wer(self):
        self.assertEqual(2, evaluate.distance(['один', 'два', 'три'], ['один', 'дваа', 'четыре', 'три']))
        self.assertEqual(2, evaluate.distance([], ['да', 'нет']))

    def test_normalization_retains_hesitations(self):
        self.assertEqual(['ёлка'.replace('ё', 'е'), 'да', 'ээ', '42'], evaluate.words([{'text': 'Ёлка, ДА… ээ 42'}]))

    def test_rejects_boolean_timestamps_and_unsupported_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'data.json'
            for turn in [dict(speaker_id='speaker_1', start_ms=False, end_ms=100, text='да'),
                         dict(speaker_id='speaker_1', start_ms=0, end_ms=100, text='да', score=1)]:
                path.write_text(json.dumps({'transcript': [turn]}))
                with self.assertRaises(ValueError):
                    evaluate.load(path)

    def test_accepts_overlap_without_dropping_it(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'data.json'
            path.write_text(json.dumps({'transcript': [
                dict(speaker_id='overlap', start_ms=0, end_ms=100, text='да'),
                dict(speaker_id='speaker_1', start_ms=50, end_ms=150, text='нет')]}))
            self.assertEqual(2, len(evaluate.load(path)))


if __name__ == '__main__':
    unittest.main()
