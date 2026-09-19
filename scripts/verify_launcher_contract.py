#!/usr/bin/env python3
"""Verify ColorDuo's reflection contract against an apktool-decoded launcher.

This checks static structure only; it does not prove LSPosed or GPU behavior.
No launcher APK, DEX, or decompiled source is bundled with ColorDuo.
"""
import argparse
import json
from pathlib import Path
import re

L='com/android/launcher'
W='com/android/launcher3'
SLANT=L+'/effect/agent/SlantEffectAgent'
BASE=L+'/effect/EffectAgent'
WORKSPACE=W+'/OplusWorkspace'
STATE=W+'/LauncherState'
CONTROLLER=L+'/effect/EffectController'
CELL=W+'/CellLayout'

class Smali:
    def __init__(self,root):
        self.roots=sorted(root.glob('smali*'))
        self.cache={}
    def get(self,name):
        if name not in self.cache:
            paths=[r/(name+'.smali') for r in self.roots]
            path=next((p for p in paths if p.is_file()),None)
            if path is None: raise ValueError('Missing class: '+name)
            text=path.read_text(encoding='utf-8-sig')
            if not re.search(r'^\.class .* L'+re.escape(name)+r';$',text,re.M):
                raise ValueError('Class descriptor mismatch: '+name)
            parent=re.search(r'^\.super L([^;]+);',text,re.M)
            methods=re.findall(r'^\.method .*? ([^\s]+\([^\n]+)$',text,re.M)
            fields=re.findall(r'^\.field .*? ([^\s:]+:[^\s=]+)',text,re.M)
            self.cache[name]=(parent.group(1) if parent else None,methods,fields,text)
        return self.cache[name]
    def resolve(self,owner,signature,kind):
        seen=set()
        while owner and owner not in seen:
            seen.add(owner)
            parent,methods,fields,_=self.get(owner)
            if signature in (methods if kind=='method' else fields):return owner
            owner=parent
        raise ValueError('Missing '+kind+': '+signature)

def check(root):
    smali=Smali(root)
    rows=[]
    methods=[
        (SLANT,'applySlantEffect(I)V'),(SLANT,'restoreParameters()V'),
        (BASE,'recycle()V'),(BASE,'interceptEffectWhenSwitchingState()Z'),
        (WORKSPACE,'getVisibleChildrenRange()[I'),
        (WORKSPACE,'onPageBeginTransition()V'),(WORKSPACE,'onPageEndTransition()V'),
        (WORKSPACE,'onDetachedFromWindow()V'),(WORKSPACE,'setState(L'+STATE+';)V'),
        (WORKSPACE,'setStateWithAnimation(L'+STATE+';L'+W+'/states/StateAnimationConfig;L'+W+'/anim/PendingAnimation;)V'),
        (W+'/Workspace','isSwitchingState()Z'),
        (W+'/statemanager/StatefulActivity','isInState(L'+W+'/statemanager/BaseState;)Z'),
        (L+'/Launcher','getWorkspace()L'+WORKSPACE+';'),
        (CONTROLLER,'getEffectAgent()L'+BASE+';'),(CONTROLLER,'resetEffect()V'),
        (CELL,'dispatchDraw(Landroid/graphics/Canvas;)V'),(CELL,'enableHardwareLayer(Z)V'),
    ]
    fields=[(BASE,'mWorkspace:L'+WORKSPACE+';'),(BASE,'mLauncher:L'+L+'/Launcher;'),
            (WORKSPACE,'mEffectController:L'+CONTROLLER+';'),(STATE,'NORMAL:L'+STATE+';')]
    for kind,items in [('method',methods),('field',fields)]:
        for owner,signature in items:
            try:
                resolved=smali.resolve(owner,signature,kind)
                rows.append(dict(kind=kind,owner=owner,signature=signature,resolved=resolved,passed=True))
            except ValueError as error:
                rows.append(dict(kind=kind,owner=owner,signature=signature,passed=False,error=str(error)))
    assertions=[('slant hierarchy',lambda:smali.get(SLANT)[0]==BASE),
        ('page drawing is inherited',lambda:smali.resolve(W+'/OplusCellLayout','dispatchDraw(Landroid/graphics/Canvas;)V','method')==CELL),
        ('NORMAL is static',lambda:bool(re.search(r'^\.field [^\n]*\bstatic\b[^\n]* NORMAL:L'+re.escape(STATE)+r';',smali.get(STATE)[3],re.M))),
        ('slant uses page rotation',lambda:'Landroid/view/View;->setRotationY(F)V' in smali.get(SLANT)[3])]
    for label,test in assertions:
        try: rows.append(dict(kind='behavior-shape',name=label,passed=bool(test())))
        except ValueError as error: rows.append(dict(kind='behavior-shape',name=label,passed=False,error=str(error)))
    return dict(passed=all(r['passed'] for r in rows),checks=len(rows),runtime_verified=False,results=rows)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('decoded',type=Path)
    parser.add_argument('--json',type=Path)
    args=parser.parse_args()
    result=check(args.decoded)
    if args.json:args.json.write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(('PASS' if result['passed'] else 'FAIL')+': '+str(result['checks'])+' static checks; runtime NOT verified')
    for row in result['results']:
        if not row['passed']:print(json.dumps(row))
    raise SystemExit(0 if result['passed'] else 1)
