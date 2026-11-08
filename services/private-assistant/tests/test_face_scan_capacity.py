"""Request-local connection reuse must stay bounded even with a full eligible roster."""
import json
from contextlib import contextmanager
import numpy as np
import pytest
from assistant.face_identification import FaceIdentification


@pytest.mark.parametrize('fail',[False,True])
def test_scan_connection_cache_is_bounded_and_closed_on_every_exit(fail):
    active = 0; maximum = 0
    class Connection:
        def execute(self,*args):pass
    class Databases:
        @contextmanager
        def connect(self,uid):
            nonlocal active,maximum
            active+=1;maximum=max(maximum,active)
            try:yield Connection()
            finally:active-=1
    identify=FaceIdentification(None,Databases(),None)
    def versions(ids,deadline,*,connections,control):
        for uid in ids:
            connections(uid)
        if fail:raise RuntimeError('synthetic scan interruption')
        return {}
    identify.versions=versions
    row={'roster':json.dumps(list(range(1,101))),'versions':'{}'}
    if fail:
        with pytest.raises(RuntimeError,match='interruption'):
            identify._match(np.eye(128)[0],row,control=object())
    else:
        assert identify._match(np.eye(128)[0],row,control=object()) is None
    assert maximum==4 and active==0
