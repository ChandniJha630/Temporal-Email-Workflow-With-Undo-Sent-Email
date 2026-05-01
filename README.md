# Email Workflow — Temporal Java SDK

A simple Temporal workflow that waits 1 minute, then sends an email — with retry on failure and a cancel signal to abort before sending.

```
Start → Sleep(1 min) → Check cancel flag
                            ├── cancelled? → return "Cancelled"
                            └── not cancelled? → sendEmail (with retry) → Done
```

---

## Project Structure

```
app/src/main/java/emailworkflow/
├── EmailWorkflow.java        # Workflow interface
├── EmailWorkflowImpl.java    # Workflow implementation (orchestration)
├── EmailActivities.java      # Activity interface
├── EmailActivitiesImpl.java  # Activity implementation (email sending)
├── EmailWorker.java          # Worker bootstrap (registers workflow + activities)
└── EmailStarter.java         # Starts the workflow (client)
```

---

## How to Run

Prerequisites: a running [Temporal server](https://docs.temporal.io/cli#start-dev-server) (`temporal server start-dev`).

```bash
# Terminal 1 — start the worker
./gradlew :app:runWorker

# Terminal 2 — start the workflow
./gradlew :app:runStarter
```

To test cancellation, uncomment the cancel lines in `EmailStarter.java` before running the starter.

---

## Key Concepts

### Workflow vs Activity

Both are Java interfaces, but they serve very different purposes:

| | Workflow | Activity |
|---|---------|----------|
| Annotations | `@WorkflowInterface`, `@WorkflowMethod`, `@SignalMethod` | `@ActivityInterface`, `@ActivityMethod` |
| Purpose | Orchestration — controls the flow (sleep, check cancel, call activities) | Side effects — sending emails, calling APIs, writing to a DB |
| Rules | Must be deterministic (no direct I/O, no random, no real clocks) | No restrictions — this is where real work happens |
| Durability | Temporal replays workflow code for fault tolerance | Activities can fail and get retried independently |

### Task Queue

The task queue (`EMAIL_TASK_QUEUE`) is the routing mechanism between the starter and the worker. Think of it as a named mailbox.

```
Starter  ──▶  Temporal Server  ──▶  Worker
         "run on EMAIL_TASK_QUEUE"    (listening on EMAIL_TASK_QUEUE)
```

- The **worker** registers itself to listen on `EMAIL_TASK_QUEUE` — "I can handle email workflows and activities."
- The **starter** submits a workflow to `EMAIL_TASK_QUEUE` — "Run this on whatever worker is listening here."
- The **Temporal server** matches them — it puts the task in the queue, and the worker picks it up.

Why it matters:
- Different task queues for different workloads (e.g., `EMAIL_TASK_QUEUE`, `PAYMENT_TASK_QUEUE`)
- Scale workers independently per queue
- If no worker is listening, tasks wait in the queue until one comes online (nothing is lost)
- Route specific activities to specialized workers (e.g., CPU-heavy work on beefy machines)

The queue name is just a string — it has no special meaning to Temporal. It's the contract between your starter and your worker.

### Scaling Workers

There is no single config that says "this queue gets N workers." You scale in two ways:

#### Horizontal scaling — multiple worker processes

Run `EmailWorker.main()` multiple times. Each process registers on the same queue and Temporal distributes tasks across them.

```bash
# Terminal 1
./gradlew :app:runWorker

# Terminal 2
./gradlew :app:runWorker

# Terminal 3
./gradlew :app:runWorker
```

That's 3 workers on `EMAIL_TASK_QUEUE`. Temporal load-balances automatically. In production, deploy multiple instances via containers, Kubernetes pods, ECS tasks, etc.

#### Vertical scaling — tuning concurrency per worker

Each worker process has internal thread pools configured with `WorkerOptions`:

```java
Worker worker = factory.newWorker(
    TASK_QUEUE,
    WorkerOptions.newBuilder()
        .setMaxConcurrentWorkflowTaskExecutionSize(200)   // default 200
        .setMaxConcurrentActivityExecutionSize(200)       // default 200
        .setMaxConcurrentLocalActivityExecutionSize(200)  // default 200
        .build());
```

- **WorkflowTaskExecutionSize** — how many workflow tasks this worker handles concurrently
- **ActivityExecutionSize** — how many activities run in parallel on this worker
- **LocalActivityExecutionSize** — same but for local activities

### Concurrency

Concurrency means handling multiple tasks at the same time within a single worker process.

```
Concurrency = 1              Concurrency = 3

 Task A ██████████            Task A ██████████
 Task B     ██████████        Task B ██████████
 Task C         ██████        Task C ██████████
                              ↑ all running simultaneously
 ↑ one after another
```

Setting `setMaxConcurrentActivityExecutionSize(200)` means one worker process can run up to 200 activities simultaneously. If 200 emails need to be sent, it doesn't wait for email #1 to finish before starting #2.

- Set it too high → machine runs out of memory/CPU or overwhelms the email service
- Set it too low → tasks pile up in the queue and take longer to complete

### Threading Model

**Activities use multiple threads.** Each activity execution gets its own thread from a thread pool.

```
Worker Process (single JVM)
┌─────────────────────────────────┐
│  Activity Thread Pool (max 200) │
│                                 │
│  Thread-1  → sendEmail("a@...") │
│  Thread-2  → sendEmail("b@...") │
│  Thread-3  → sendEmail("c@...") │
│  ...                            │
│  Thread-200→ sendEmail("z@...") │
└─────────────────────────────────┘
```

**Workflows are also multi-threaded**, but each workflow task is lightweight — it's just replaying deterministic code (no I/O). The workflow thread pool handles many more workflows per thread than activities.

| | Activities | Workflows |
|---|---|---|
| Threading | 1 thread per activity execution | 1 thread per workflow task |
| Work type | Heavy (I/O, network calls) | Lightweight (decision logic, replay) |
| Why separate threads | Each `sendEmail` could block on network | Replay is fast, mostly CPU-bound |

### Retry Configuration

Configured on the activity stub in `EmailWorkflowImpl`:

```java
RetryOptions.newBuilder()
    .setMaximumAttempts(3)
    .setInitialInterval(Duration.ofSeconds(2))
    .setBackoffCoefficient(2.0)
    .build()
```

- Up to **3 attempts** total
- **2-second** initial backoff, **doubling** each retry (2s → 4s → 8s)
- **10-second** timeout per attempt (`setStartToCloseTimeout`)

### Cancel Signal

The workflow exposes a `@SignalMethod` called `cancel()`. Sending this signal during the 1-minute sleep window sets a boolean flag. After the sleep completes, the workflow checks the flag and skips sending if it was cancelled.

```java
// From the starter or any Temporal client:
workflow.cancel();
```

This is a graceful cancellation — the workflow completes normally with a "cancelled" result rather than being forcefully terminated.
