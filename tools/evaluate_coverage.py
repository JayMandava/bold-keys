#!/usr/bin/env python3
"""One-shot sealed, source-specific held-out comparison. Exports aggregates, never source/legacy rows."""
import argparse
import collections
import json
import random
import statistics
import subprocess
import time
from pathlib import Path
import data_pipeline as data
import prediction_coverage as coverage


def parse(blob):
    return {a: b.split(',') for a, b in (line.split('\t') for line in blob.decode().splitlines())}


def windows(words):
    return {' '.join(words[i:i + 5]) for i in range(len(words) - 4)}


def clean_test(lang, raw, training, diagnostics):
    exact = set(training) | set(data.sentences('\n'.join(diagnostics)))
    token_exact = {' '.join(data.tokens(lang, s)) for s in exact}
    near = set()
    for s in exact:
        near.update(windows(data.tokens(lang, s)))
    counts = collections.Counter(input_unique=len(raw))
    clean = []
    for s in raw:
        words = data.tokens(lang, s)
        if s in exact:
            counts['excluded_exact'] += 1
        elif ' '.join(words) in token_exact:
            counts['excluded_token_exact'] += 1
        elif windows(words) & near:
            counts['excluded_shared_five_token_window'] += 1
        elif not any(a != b and coverage.lexical(lang, a) and coverage.lexical(lang, b) for a, b in zip(words, words[1:])):
            counts['excluded_no_eligible_transition'] += 1
        else:
            clean.append(s)
    clean.sort(key=lambda s: data.sha(s.encode()))
    counts['clean_before_cap'] = len(clean)
    counts['selected'] = min(len(clean), 2000)
    return clean[:2000], dict(counts)


def observations(lang, prompts):
    # All eligible adjacent transitions, including out-of-dictionary and unseen words.
    return [[(a, b) for a, b in zip(ws, ws[1:]) if a != b and coverage.lexical(lang, a) and coverage.lexical(lang, b)]
            for ws in (data.tokens(lang, s) for s in prompts)]


def metrics(model, by_prompt, training_contexts):
    pairs = [p for row in by_prompt for p in row]
    total = len(pairs)
    groups = {'unseen': [], 'rare_1_4': [], 'medium_5_19': [], 'common_20_plus': []}
    for a, b in pairs:
        n = training_contexts[a]
        groups['unseen' if n == 0 else 'rare_1_4' if n <= 4 else 'medium_5_19' if n < 20 else 'common_20_plus'].append((a,b))
    def rates(rows):
        n = len(rows)
        return dict(transitions=n, context_coverage=sum(a in model for a,b in rows)/n if n else None,
                    **{'top'+str(k):sum(b in model.get(a, [])[:k] for a,b in rows)/n if n else None for k in (1,2,4)})
    contexts = collections.defaultdict(list)
    for a,b in pairs:
        contexts[a].append(b)
    return dict(**rates(pairs), distinct_contexts=len(contexts), unique_context_coverage=sum(a in model for a in contexts)/len(contexts),
                macro_context_top2=statistics.mean(sum(b in model.get(a,[])[:2] for b in bs)/len(bs) for a,bs in contexts.items()),
                by_training_context_support={key:rates(value) for key,value in groups.items()})


def paired_interval(candidate, other, by_prompt, seed):
    rng = random.Random(seed)
    scores = [(sum(b in candidate.get(a,[])[:2] for a,b in row)-sum(b in other.get(a,[])[:2] for a,b in row), len(row)) for row in by_prompt]
    samples=[]
    for _ in range(400):
        chosen = rng.choices(scores, k=len(scores))
        samples.append(sum(x for x,n in chosen)/sum(n for x,n in chosen))
    samples.sort()
    return {'method':'400 paired prompt-cluster bootstrap resamples; descriptive 95% percentile interval, source-specific',
            'top2_difference':sum(x for x,n in scores)/sum(n for x,n in scores), 'lower':samples[10], 'upper':samples[389]}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-dir',type=Path,required=True)
    parser.add_argument('--heldout-dir',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    plan=data.read_json(data.DATA/'coverage_evaluation_plan.json')
    seal=data.read_json(data.DATA/'coverage_model_seal.json')
    for name,digest in seal['sha256'].items():
        if data.sha((data.ROOT/name).read_bytes()) != digest:
            raise ValueError('Sealed model or plan changed: '+name)
    result={'plan_sha256':data.sha((data.DATA/'coverage_evaluation_plan.json').read_bytes()),
            'seal':seal,'interpretation':'Raw TSV lookup, not phone/user accuracy. No spelling-dictionary eligibility filter in denominator. English runtime dialect filtering may remove/map hints. One previous word only; no learned/LLM hints. Legacy training overlap cannot be ruled out.',
            'languages':{}}
    scenarios=data.read_json(data.DATA/'prediction_scenarios.json')
    for lang in data.LANGS:
        training=coverage.load_training(args.source_dir,lang)
        raw=[];files=[]
        for item in plan['files']:
            if item['language'] != lang:continue
            blob=(args.heldout_dir/item['filename']).read_bytes()
            if data.sha(blob)!=item['sha256']:raise ValueError('Heldout source digest mismatch')
            raw.extend(blob.decode().splitlines());files.append(item)
        diagnostics=[prefix+target for _,prefix,_,targets in scenarios[lang] for target in targets]
        prompts,contamination=clean_test(lang,data.sentences('\n'.join(raw)),training,diagnostics)
        by_prompt=observations(lang,prompts)
        _,_,support=coverage.corpus_statistics(lang,training)
        name='app/src/main/assets/'+data.pair_name(lang)
        blobs={'candidate':(data.ROOT/name).read_bytes()}
        for key,revision in [('prior',plan['prior_candidate']),('legacy',plan['legacy_baseline'])]:
            blobs[key]=subprocess.check_output(['git','show',revision+':'+name],cwd=data.ROOT)
        models={key:parse(blob) for key,blob in blobs.items()}
        start=time.perf_counter();regenerated=coverage.model(lang,training);elapsed=time.perf_counter()-start
        if regenerated!=models['candidate']:raise ValueError('Candidate regeneration differs from seal')
        synthetic={}
        for key,m in models.items():
            synthetic[key]={group:dict(cases=sum(g==group for g,*_ in scenarios[lang]),
                                      top2_hits=sum(any(t in m.get(context,[])[:2] for t in targets) for g,_,context,targets in scenarios[lang] if g==group))
                            for group in ('chat','general','tail')}
        result['languages'][lang]=dict(files=files,contamination=contamination,
             test_selection_sha256=data.sha('\n'.join(data.sha(s.encode()) for s in prompts).encode()),
             metrics={key:metrics(model,by_prompt,support) for key,model in models.items()},
             paired_candidate_minus={key:paired_interval(models['candidate'],models[key],by_prompt,17) for key in ('prior','legacy')},
             tables={key:dict(contexts=len(model),followers=sum(map(len,model.values())),single_follower_contexts=sum(len(v)==1 for v in model.values()),bytes=len(blobs[key]),sha256=data.sha(blobs[key])) for key,model in models.items()},
             candidate_generation_seconds=elapsed,
             legacy_contexts_retained=sum(w in models['candidate'] for w in models['legacy']),
             synthetic_diagnostic_biased_not_heldout=synthetic)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
    print(json.dumps({lang:dict(contamination=v['contamination'],metrics={k:{x:m[x] for x in ('transitions','context_coverage','top1','top2','top4')} for k,m in v['metrics'].items()}, tables=v['tables']) for lang,v in result['languages'].items()},indent=2))

if __name__=='__main__':main()
