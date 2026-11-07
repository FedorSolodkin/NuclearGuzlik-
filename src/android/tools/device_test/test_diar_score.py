import unittest
from diar_score import score


class DerTest(unittest.TestCase):
    def test_global_permutation(self):
        ref = [[0, 1000, 'A'], [1000, 2000, 'B'], [2000, 3000, 'A']]
        hyp = [[0, 1000, 8], [1000, 2000, 3], [2000, 3000, 8]]
        self.assertEqual(score(ref, hyp)['der'], 0)

    def test_collapse_is_not_perfect(self):
        r = score([[0, 1000, 'A'], [1000, 2000, 'B']], [[0, 2000, 0]])
        self.assertEqual(r['der'], .5)
        self.assertEqual(r['confusion_ms'], 1000)

    def test_missing_overlap_counts(self):
        r = score([[0, 1000, 'A'], [0, 1000, 'B']], [[0, 1000, 'a']])
        self.assertEqual(r['der'], .5)
        self.assertEqual(r['missed_ms'], 1000)

    def test_false_alarm_and_missed_speech(self):
        r = score([[0, 1000, 'A']], [[500, 1500, 0]])
        self.assertEqual(r['der'], 1)
        self.assertEqual(r['missed_ms'], 500)
        self.assertEqual(r['false_alarm_ms'], 500)

    def test_duplicate_windows_do_not_duplicate_speaker_time(self):
        self.assertEqual(score([[0, 1000, 'A']], [[0, 1000, 0], [500, 1000, 0]])['der'], 0)

    def test_per_turn_mapping_would_hide_identity_switch(self):
        r = score([[0, 100, 'A'], [100, 200, 'B'], [200, 300, 'A'], [300, 400, 'B']],
                  [[0, 100, 0], [100, 200, 1], [200, 300, 1], [300, 400, 0]])
        self.assertEqual(r['der'], .5)

    def test_empty_and_invalid_inputs(self):
        self.assertIsNone(score([], [[0, 100, 0]])['der'])
        self.assertEqual(score([[0, 100, 'A']], [])['der'], 1)
        with self.assertRaises(ValueError):
            score([[10, 0, 'A']], [])
        with self.assertRaises(ValueError):
            score([[0, float('nan'), 'A']], [])


if __name__ == '__main__':
    unittest.main()
