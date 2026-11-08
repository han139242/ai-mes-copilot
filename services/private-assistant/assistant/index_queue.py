"""Wake current users immediately; periodically discover durable jobs after restarts.

This queue holds only user IDs. Jobs and processing consent remain in the user's
database, so losing an in-memory notification cannot lose or authorize work.
"""
import asyncio
import itertools
import logging
from datetime import datetime, timezone

logger = logging.getLogger(__name__)


class IndexQueue:
    def __init__(self, databases, knowledge, sweep_seconds=30):
        self.databases, self.knowledge = databases, knowledge
        self.sweep_seconds = sweep_seconds
        self.queue = asyncio.PriorityQueue()
        self.pending = {}
        self.sequence = itertools.count()
        self.recovered = set()
        self.started = datetime.now(timezone.utc)

    def notify(self, uid, urgent=True):
        # Called on the ASGI event loop, after the mutation transaction commits.
        priority = 0 if urgent else 1
        previous = self.pending.get(uid)
        if previous is not None and previous <= priority:
            return
        self.pending[uid] = priority
        self.queue.put_nowait((priority, next(self.sequence), uid))

    def collect(self, uid):
        self.databases.ensure(uid)
        self.knowledge.cleanup_files(uid)
        with self.databases.index_lease(uid) as acquired:
            if not acquired:
                return []  # An active service/script owns this user's indexing.
            with self.databases.connect(uid) as db:
                if uid not in self.recovered:
                    db.execute("""UPDATE jobs SET state='failed',error='服务重启，请重试任务',finished=now()
                        WHERE state='running' AND started<%s""", (self.started,))
                db.execute("""UPDATE jobs SET state='failed',error='处理超时，可重试任务',finished=now()
                    WHERE state='running' AND started<now()-interval '35 minutes'""")
                jobs = db.execute("SELECT id FROM jobs WHERE state='queued' ORDER BY created LIMIT 1").fetchall()
        self.recovered.add(uid)
        return jobs

    async def run_one(self):
        priority, _, uid = await self.queue.get()
        try:
            if self.pending.get(uid) != priority:
                return  # A newer urgent notification superseded this sweep entry.
            del self.pending[uid]
            jobs = await asyncio.to_thread(self.collect, uid)
            for job in jobs:
                await asyncio.to_thread(self.knowledge.rebuild, uid, job['id'])
            if jobs:
                self.notify(uid, urgent=False)  # Fairly revisit changes made during indexing.
        except Exception:
            # One unavailable user database must not prevent all other indexing.
            logger.warning('Private index queue will retry an unavailable user on the next sweep')
        finally:
            self.queue.task_done()

    async def process(self):
        while True:
            await self.run_one()

    async def discover(self):
        while True:
            try:
                for uid in await asyncio.to_thread(self.databases.users):
                    self.notify(uid, urgent=False)
            except Exception:
                logger.warning('Private job discovery is waiting for database availability')
            await asyncio.sleep(self.sweep_seconds)
