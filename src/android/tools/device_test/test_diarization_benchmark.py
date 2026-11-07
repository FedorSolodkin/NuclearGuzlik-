import io
import tempfile
import unittest
import zipfile
import re
from pathlib import Path
from unittest.mock import patch
from prepare_diarization_benchmark import RangeReader, read_references, select_cases
from evaluate_diarization_benchmark import diagnostics, merged_length, speech_recall, word_attribution
from diar_score import score


class BenchmarkTest(unittest.TestCase):
    def test_range_reader_refuses_full_archive_response(self):
        response=io.BytesIO(b'huge archive')
        response.status=200
        with patch('urllib.request.urlopen', return_value=response):
            with self.assertRaises(ValueError): RangeReader('https://example.org/audio.zip')

    def test_partial_archive_reads_deliver_the_selected_member_with_crc_check(self):
        archive=io.BytesIO()
        with zipfile.ZipFile(archive,'w') as z:
            z.writestr('unneeded.bin',b'x'*200000)
            z.writestr('selected.wav',b'reference audio')
        blob=archive.getvalue()
        def serve(request,timeout):
            start,end=map(int,re.fullmatch(r'bytes=(\d+)-(\d+)',request.get_header('Range')).groups())
            response=io.BytesIO(blob[start:end+1]);response.status=206
            response.headers={'Content-Range':f'bytes {start}-{end}/{len(blob)}','ETag':'"fixed"'}
            return response
        with patch('urllib.request.urlopen',side_effect=serve):
            with zipfile.ZipFile(RangeReader('https://example.org/audio.zip')) as z:
                self.assertEqual(z.read('selected.wav'),b'reference audio')

    def test_correct_identity_recall_does_not_reward_duplicate_windows(self):
        result=speech_recall([[0,1000,'Alice']],[[0,1000,0],[0,1000,0]],{'0':'Alice'})
        self.assertEqual(result['recall'],1)
        self.assertEqual(merged_length([(0,1000),(500,1500)]),1500)

    def test_short_reply_does_not_get_a_new_optimistic_mapping(self):
        ref=[[0,9000,'Alice'],[9000,9500,'Bob']]
        hyp=[[0,9500,0]]
        metrics=score(ref,hyp)
        result=diagnostics(ref,hyp,metrics['mapping'])
        self.assertEqual(result['short_replies'],1)
        self.assertEqual(result['short_reply_identity_recall']['recall'],0)

    def test_rare_voice_and_overlap_are_measured_from_gold(self):
        ref=[[0,10000,'Alice'],[5000,5200,'Bob']]
        hyp=[[0,10000,0]]
        result=diagnostics(ref,hyp,score(ref,hyp)['mapping'])
        self.assertEqual(result['overlap_wall_ms'],200)
        self.assertEqual(result['rare_speakers'],['Bob'])
        self.assertEqual(result['rare_speaker_identity_recall']['recall'],0)

    def test_reference_loader_reads_test_only_and_converts_duration(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive=Path(temporary)/'annotations.zip'
            with zipfile.ZipFile(archive,'w') as z:
                z.writestr('vox/test/a.rttm','SPEAKER a 1 1.250 0.500 <NA> <NA> person <NA> <NA>\n')
                z.writestr('vox/dev/b.rttm','SPEAKER b 1 0 1 <NA> <NA> other <NA> <NA>\n')
            self.assertEqual(read_references(archive),{'a':[[1250,1750,'person']]})

    def test_selection_is_fixed_by_duration_then_id(self):
        refs={}
        for count in range(1,6):
            for suffix,end in [('z',32000),('b',31000),('a',31000)]:
                refs[f'{count}{suffix}']=[[0,end,str(person)] for person in range(count)]
        self.assertEqual(select_cases(refs),[f'{count}{suffix}' for count in range(1,6) for suffix in ('a','b')])
        self.assertTrue(set(select_cases(refs,per_count=1)).isdisjoint(select_cases(refs,per_count=1,skip_per_count=1)))

    def test_word_accuracy_counts_unknowns_as_errors_and_excludes_mixed_boundaries(self):
        reference=[[0,1000,'A'],[1000,2000,'B'],[1500,1700,'A']]
        words=[[0,500,'correct',0],[500,900,'unknown',-1],[950,1050,'boundary',0],
               [1500,1600,'overlap',1],[1800,1800,'collapsed',1],[1800,1950,'wrong',0]]
        result=word_attribution(reference,words,{'0':'A','1':'B'})
        self.assertEqual(result['eligible'],3)
        self.assertEqual(result['correct'],1)
        self.assertEqual(result['unknown_or_overlap_prediction'],1)
        self.assertEqual(result['excluded_overlap'],1)
        self.assertEqual(result['excluded_zero_duration'],1)
        self.assertEqual(result['accuracy'],1/3)


if __name__=='__main__': unittest.main()
