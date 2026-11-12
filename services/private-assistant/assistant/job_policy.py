"""Durable source and budget grants for explicitly approved synthetic index jobs.

Ordinary user jobs have no grant. A validation grant follows its job across a
crash/restart, so a default-unlimited queue cannot expand validation authority.
"""
import hashlib
import hmac
import json
import os
import re
from dataclasses import replace


def policy_path(settings, uid, job):
    if not isinstance(job,str) or not re.fullmatch(r'[a-f0-9]{32}',job):
        raise ValueError('Invalid private index job identity')
    return settings.user_dir(uid)/'job-policies'/(job+'.json')


def canonical(value):
    return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()


def save_policy(settings, uid, job, sources, ceiling):
    if type(ceiling) is not int or ceiling<0:raise ValueError('A nonnegative model ceiling is required; zero means unlimited')
    payload={'uid':uid,'jobId':job,'sources':sources,'ceiling':ceiling}
    signature=hmac.new(settings.secret.encode(),canonical(payload),hashlib.sha256).hexdigest()
    path=policy_path(settings,uid,job);path.parent.mkdir(parents=True,exist_ok=True)
    descriptor=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(descriptor,'wb') as stream:
        stream.write(canonical({'payload':payload,'signature':signature}));stream.flush();os.fsync(stream.fileno())


def load_policy(settings, uid, job):
    if not job:return None
    path=policy_path(settings,uid,job)
    if not path.exists():return None
    try:
        envelope=json.loads(path.read_text(encoding='utf-8'));payload=envelope['payload']
        expected=hmac.new(settings.secret.encode(),canonical(payload),hashlib.sha256).hexdigest()
        if (not hmac.compare_digest(expected,envelope['signature']) or payload['uid']!=uid or payload['jobId']!=job
                or type(payload['ceiling']) is not int or payload['ceiling']<0
                or not isinstance(payload['sources'],dict)):
            raise ValueError()
        return payload
    except Exception:
        raise RuntimeError('验证任务授权校验失败，未发送资料') from None


def assert_sources(policy, documents):
    if policy is None:return
    allowed=policy['sources']
    if (len(documents)!=len(allowed) or len({d['name'] for d in documents})!=len(documents)
            or any(allowed.get(d['name'])!=d['hash'] for d in documents)):
        raise RuntimeError('资料与本次批准的合成文件不一致，已停止验证任务')


def bounded_settings(settings, policy):
    if policy is None:return settings
    ceiling=policy['ceiling']
    if ceiling == 0:return settings
    return replace(settings,llm_limit=min(settings.llm_limit,ceiling) if settings.llm_limit>0 else ceiling)
