import random, subprocess, re, sys
random.seed(int(sys.argv[1]) if len(sys.argv)>1 else 1)
src=open('core/src/main/kotlin/splitziner/edit/Templates.kt').read()
tpls={}
for m in re.finditer(r'Template\("(\w+)".*?listOf\((.*?)\),\s*\)\s*\{', src, re.S):
    pass
# simpler: parse Template( key ... ) blocks by Field occurrences between Template( lines
cur=None
for line in src.split('\n'):
    t=re.search(r'Template\("(\w+)"', line)
    if t: cur=t.group(1); tpls[cur]=[]
    for f in re.finditer(r'Field\("(\w+)", "[^"]*", ([0-9.]+), ([0-9.]+)', line):
        if cur: tpls[cur].append((f.group(1), float(f.group(2))))
fails=0
for key, fields in tpls.items():
    for trial in range(6):
        vals=[]
        for name,dflt in fields:
            if dflt in (0.0,1.0) and name in ('pins','overlay','graduated','doors'):
                v=random.choice([0,1]) if name!='doors' else random.choice([0,1,2])
            else:
                v=round(dflt*random.choice([0.5,0.75,1.0,1.5,2.0]),3)
            vals.append(f"{name}={v}")
        env=dict(__import__('os').environ, TPL=",".join(vals), GUIDETEXT="1")
        r=subprocess.run(['cli/build/install/cli/bin/cli',f'template:{key}','--out','/tmp/fz','--full','--display','in','--fraction'],env=env,capture_output=True,text=True,timeout=600)
        out=r.stdout+r.stderr
        bad = ('Exception' in out) or (r.returncode!=0 and 'do not fit' not in out)
        anom=[l for l in out.split('\n') if l.startswith(('line','title')) and any(x in l for x in ('null','NaN','Infinity','item -',' -0 ','(0 joints)'))]
        if anom: print('ANOM', key, ','.join(vals)); print('\n'.join(anom[:3]))
        if bad:
            fails+=1
            print("FAIL", key, ",".join(vals)); print("\n".join([l for l in out.split('\n') if 'xception' in l or 'at splitziner' in l][:6]))
print("fails", fails, "templates", list(tpls))
