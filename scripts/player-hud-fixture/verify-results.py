#!/usr/bin/env python3
from pathlib import Path
import argparse,hashlib,json,re
parser=argparse.ArgumentParser(description="Require expected actual-client HUD regression evidence")
parser.add_argument("--work-dir",type=Path,required=True)
parser.add_argument("--baseline-native",default="baseline-native/verification.json")
parser.add_argument("--baseline-wit",default="baseline-wit/verification.json")
args=parser.parse_args()
ROOT=args.work_dir.resolve()

def require(ok,message):
    if not ok: raise RuntimeError(message)

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def load(relative): return json.loads((ROOT/relative).read_text())

paths={
 'baseline_native':args.baseline_native,
 'baseline_wit':args.baseline_wit,
 'candidate_native':'candidate-native/verification.json',
 'candidate_wit':'candidate-wit/verification.json',
 'candidate_restart':'candidate-native/restart-evidence/verification.json',
}
data={name:load(path) for name,path in paths.items()}
cases={name:{case['name']:case for case in proof['cases']} for name,proof in data.items()}
passed=[]

def check(name,ok): require(ok,name);passed.append(name)

def one(case,text): return len(case['bars'])==1 and text in case['bars'][0]['text'] and case['actionbar']==''

def action(case,text): return len(case['bars'])==0 and text in case['actionbar']

for name,proof in data.items():
    check(name+' connected real protocol client',proof['connected_client'] is True)
    check(name+' normal shutdown',proof['server_exit']==0)
    check(name+' no fixture or HUD failures',proof['fixture_errors']==[] and proof['hud_errors']==[] and proof['status']=='RECORDED')
    check(name+' only identified network errors',all('Failed to request yggdrasil public key' in error for error in proof['environment_errors']))

for name in ('crouching-wheat-young','crouching-wheat-mature','crouching-redstone','crouching-torch','tick-rate-one'):
    check('negative control '+name,cases['baseline_native'][name]['bars']==[])
check('negative control WIT stale double bar',len(cases['baseline_wit']['wit-stand-handoff']['bars'])==2)
check('negative control WIT invalid type suppresses HUD',cases['baseline_wit']['wit-invalid-display-fallback']['bars']==[])

native=cases['candidate_native']
for name,text in {
 'standing-stone':'Stone','crouching-stone':'Stone','crouching-wheat-young':'Age: 1/7',
 'crouching-wheat-mature':'Age: 7/7','crouching-redstone':'Redstone Wire | Power: 0/15',
 'crouching-torch':'Torch','crouching-electric-furnace':'Electric Furnace | Idle | 0/64 J',
 'crouching-solar-generator':'Solar Generator | ☀ 2 J/t','crouching-dropped-diamonds':'Diamond | Stack: 12',
 'tick-rate-one':'Stone','tick-rate-two':'Stone','tick-rate-five':'Stone','native-toggle-on':'Stone',
}.items(): check(name,one(native[name],text))
for name in ('actionbar-stone','saved-actionbar-preference'):check(name,action(native[name],'Stone'))
for name in ('actionbar-clear-on-look-away','native-toggle-off'):check(name,native[name]['bars']==[] and native[name]['actionbar']=='')

wit=cases['candidate_wit']
for name,text in {
 'wit-standing-native':'Stone','wit-crouching-single-bar':'Stone','wit-stand-handoff':'Stone',
 'wit-invalid-display-fallback':'Stone','wit-valid-display-recovery':'Stone',
 'wit-electric-furnace':'Electric Furnace | Idle | 0/64 J','wit-solar-generator':'Solar Generator | ☀ 2 J/t',
 'wit-crouching-wheat-young':'Wheat | Age: 1/7','wit-disabled-native-fallback':'Stone','wit-enabled-recovery':'Stone',
 'wit-final-stand-handoff':'Stone','wit-actionbar-stand-handoff':'Stone','wit-bossbar-after-actionbar':'Stone',
 'wit-reload-single-display':'Stone',
}.items():check(name,one(wit[name],text))
for name in ('wit-actionbar-crouching','wit-actionbar-resume'):check(name,action(wit[name],'Stone'))

restart=cases['candidate_restart']
for name in ('restart-restores-actionbar','restart-crouching-stone'):check(name,action(restart[name],'Stone'))
check('restart-clear-on-look-away',restart['restart-clear-on-look-away']['bars']==[] and restart['restart-clear-on-look-away']['actionbar']=='')
check('restart preserves preference bytes and mtime',data['candidate_restart']['preferences_unchanged'] is True)

candidate_hash=data['candidate_native']['addon_sha256']
for name in ('candidate_native','candidate_wit','candidate_restart'):
    check(name+' identical candidate JAR',data[name]['addon_sha256']==candidate_hash)
check('candidate JAR remains unchanged',sha(ROOT/'downloads/SF_SlimeHUD2.0.5-verified-candidate.jar')==candidate_hash)

summary={
 'status':'PASS','candidate_sha256':candidate_hash,
 'baseline_sha256':data['baseline_native']['addon_sha256'],
 'server':'Paper 1.21.11 build132',
 'server_sha256':data['candidate_native']['server_sha256'],
 'java':'Temurin 25.0.4.1+1; plugin bytecode Java21',
 'slimefun':'4.1.76 source e7ba40469eae1aa54b4347af19e58341f492edcb',
 'slimefun_sha256':data['candidate_native']['core_sha256'],
 'wit':'1.9.5-mc.1.21.11 Modrinth CWSl11QK; packaged defaults with core.mode=sneaking',
 'wit_sha256':data['candidate_wit']['wit_sha256'],
 'fixture_sha256':data['candidate_native']['fixture_sha256'],
 'candidate_cases':sum(len(data[name]['cases']) for name in ('candidate_native','candidate_wit','candidate_restart')),
 'acceptance_checks':len(passed),'checks':passed,
 'evidence':{name:{'path':str(ROOT/path),'sha256':sha(ROOT/path)} for name,path in paths.items()},
 'limitations':[
 'A real offline Java protocol client was connected to a disposable localhost Paper server; no graphical client font/rendering or production AlbionMC server was tested.',
 'Runtime gameplay-path tests used Paper1.21.11 only; Folia multi-region handoff and native26.3 gameplay remain outside this packet-level run.',
 'The fixture placed real blocks and registered Slimefun machine identities to exercise production HUD code; it did not simulate every addon handler, machine recipe, or inventory interaction.',
 'Mojang authlib public-key lookup failed due isolated-environment DNS. Native logs preserve this network error; no SlimeHUD/WIT/fixture runtime failures were found.',
 ],
}
(ROOT/'runtime-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
print(json.dumps({key:summary[key] for key in ('status','candidate_sha256','candidate_cases','acceptance_checks')},indent=2))
