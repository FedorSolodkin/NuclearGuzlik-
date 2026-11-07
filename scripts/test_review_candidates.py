import unittest
from prepare_review_candidates import candidate, disagreement


class CandidateTest(unittest.TestCase):
    def test_speaker_change_splits_and_same_voice_combines_without_losing_words(self):
        data, changed = candidate([[0,100,'Да.',0],[150,300,'Конечно.',0],[400,600,'Нет.',1],[650,800,'Думаю.',1]],1000)
        self.assertEqual(['Да. Конечно.', 'Нет. Думаю.'], [x['text'] for x in data['transcript']])
        self.assertEqual(['speaker_1','speaker_2'], [x['speaker_id'] for x in data['transcript']])
        self.assertEqual([],changed)

    def test_uncertainty_and_adjusted_timestamps_remain_auditable(self):
        data, changed = candidate([[100,100,'Да',-1],[200,400,'Нет',-2]],300)
        self.assertEqual(['unknown','overlap'],[x['speaker_id'] for x in data['transcript']])
        self.assertEqual(2,len(changed))
        self.assertEqual(300,data['transcript'][1]['end_ms'])

    def test_asr_disagreement_is_not_claimed_as_reference_error(self):
        queue=disagreement([dict(text='Я в Ухте',startMs=0,endMs=1000)],
                           [dict(text='Я в Ухты',startMs=0,endMs=1000)])
        self.assertEqual(1,len(queue))
        self.assertEqual('ухте',queue[0]['large_v3'])
        self.assertIn('not_a_proven_error',queue[0]['reason'])


if __name__ == '__main__':
    unittest.main()
