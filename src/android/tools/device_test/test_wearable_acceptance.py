import unittest
from wearable_asr_acceptance import words, edits, summarize

class WearableAcceptanceScorerTest(unittest.TestCase):
    def test_russian_normalization_and_insert_delete_substitute(self):
        self.assertEqual(words('Ёж, ПРИВЕТ!'), ['еж', 'привет'])
        self.assertEqual(edits(['а','б','в'], ['а','г','в','д']), 2)
        self.assertEqual(edits(['а','б'], []), 2)
    def test_weighted_wer_and_rtf_use_actual_totals(self):
        cases = [{'id':'a','reference':'раз два','speakers':2}, {'id':'b','reference':'три','speakers':1}]
        result = summarize(cases, {'a':{'hyp':'раз два', 'audio_s':10,'ms':1000},
                                   'b':{'hyp':'не три', 'audio_s':2,'ms':2000}})
        self.assertAlmostEqual(result['wer'], 1/3)
        self.assertAlmostEqual(result['rtf'], 3/12)
    def test_empty_reference_is_rejected(self):
        with self.assertRaises(ValueError):
            summarize([{'id':'a','reference':'','speakers':1}], {'a':{'hyp':'а','audio_s':1,'ms':1}})

if __name__ == '__main__': unittest.main()
