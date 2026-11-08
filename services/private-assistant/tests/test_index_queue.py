import asyncio

from assistant.index_queue import IndexQueue


def test_user_action_preempts_large_recovery_backlog_without_duplicates():
    async def scenario():
        visited = []
        queue = IndexQueue(None, None)
        queue.collect = lambda uid: visited.append(uid) or []
        for uid in range(1, 101):
            queue.notify(uid, urgent=False)
        queue.notify(100)
        queue.notify(100)
        await queue.run_one()
        assert visited == [100]
        for _ in range(100):
            await queue.run_one()
        assert len(visited) == 100 and len(set(visited)) == 100
    asyncio.run(scenario())


def test_busy_user_is_requeued_and_new_upload_is_not_lost():
    async def scenario():
        calls = []
        queue = IndexQueue(None, None)
        jobs = {1: ['old'], 2: ['other']}
        queue.collect = lambda uid: [{'id': jobs[uid].pop(0)}] if jobs[uid] else []
        class Knowledge:
            def rebuild(self, uid, job):
                calls.append((uid, job))
                if job == 'old':
                    jobs[uid].append('new')
                    asyncio.run_coroutine_threadsafe(wake(), loop).result(timeout=2)
        async def wake():
            queue.notify(1)
        loop = asyncio.get_running_loop()
        queue.knowledge = Knowledge()
        queue.notify(1)
        queue.notify(2)
        for _ in range(3):
            await queue.run_one()
        assert calls == [(1, 'old'), (2, 'other'), (1, 'new')]
    asyncio.run(scenario())


def test_failed_user_does_not_block_others_and_can_be_notified_again():
    async def scenario():
        calls = []
        queue = IndexQueue(None, None)
        def collect(uid):
            calls.append(uid)
            if uid == 1 and calls == [1]:
                raise OSError('temporary database outage')
            return []
        queue.collect = collect
        queue.notify(1)
        queue.notify(2)
        await queue.run_one()
        await queue.run_one()
        queue.notify(1)
        await queue.run_one()
        assert calls == [1, 2, 1]
    asyncio.run(scenario())
