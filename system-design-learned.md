# System Design Learnings — Temporal Workers, Scaling & Kubernetes

A collection of system design concepts learned while building the Email Workflow project with the Temporal Java SDK.

---

## Table of Contents

- [Task Queues](#task-queues)
- [Multiple Activities and Queues](#multiple-activities-and-queues)
- [Horizontal Scaling](#horizontal-scaling)
- [Vertical Scaling](#vertical-scaling)
- [Concurrency](#concurrency)
- [Threading Model](#threading-model)
- [Kubernetes Deployment](#kubernetes-deployment)

---

## Task Queues

A task queue is the routing mechanism between the starter and the worker. Think of it as a named mailbox.

```
Starter  ──▶  Temporal Server  ──▶  Worker
         "run on EMAIL_TASK_QUEUE"    (listening on EMAIL_TASK_QUEUE)
```

- The **worker** registers itself to listen on a queue — "I can handle these workflows and activities."
- The **starter** submits a workflow to the same queue — "Run this on whatever worker is listening."
- The **Temporal server** matches them — puts the task in the queue, worker picks it up.

Key properties:
- Different queues for different workloads (e.g., `EMAIL_TASK_QUEUE`, `PAYMENT_TASK_QUEUE`)
- Workers can be scaled independently per queue
- If no worker is listening, tasks wait in the queue until one comes online (nothing is lost)
- The queue name is just a string — no special meaning to Temporal, just a contract between starter and worker

---

## Multiple Activities and Queues

If you have two activities and two queues, you have two options:

### Option 1: Same process, multiple workers

A single `WorkerFactory` can create multiple `Worker` instances in the same JVM:

```java
WorkerFactory factory = WorkerFactory.newInstance(client);

Worker workerA = factory.newWorker("QUEUE_A");
workerA.registerActivitiesImplementations(new EmailActivitiesImpl());

Worker workerB = factory.newWorker("QUEUE_B");
workerB.registerActivitiesImplementations(new SmsActivitiesImpl());

factory.start(); // starts both in the same process
```

### Option 2: Separate processes

Two separate JVMs, each handling one queue independently.

### When to use which

| | Same process | Separate processes |
|---|---|---|
| Simple setup | ✅ easier to deploy | More moving parts |
| Resource isolation | ❌ shared CPU/memory | ✅ one can't starve the other |
| Independent scaling | ❌ scale together | ✅ scale each queue separately |
| Failure isolation | ❌ one crash kills both | ✅ one crash doesn't affect the other |

**Rule of thumb**: lightweight and related → same process. Different resource needs or independent scaling → separate processes.

---

## Horizontal Scaling

Add more **instances** of the same worker. Each is a separate process (usually on a separate machine/container), all listening on the same task queue.

```
                    EMAIL_TASK_QUEUE
                          │
            ┌─────────────┼─────────────┐
            ▼             ▼             ▼
        Machine 1     Machine 2     Machine 3
        (Worker)      (Worker)      (Worker)
        4 CPU         4 CPU         4 CPU
        8 GB RAM      8 GB RAM      8 GB RAM
```

- Need more throughput? Add another machine.
- One machine dies? The others keep processing. Temporal re-delivers the failed tasks.
- Each machine has the same capacity — you're scaling **out**.
- Practically unlimited — just keep adding machines.
- Fault tolerant — no single point of failure.

---

## Vertical Scaling

Give a **single worker more resources** (CPU, memory, threads) so it can handle more work.

```
        Before                    After
    ┌────────────┐          ┌────────────────┐
    │  Machine 1 │          │   Machine 1    │
    │  4 CPU     │    →     │   16 CPU       │
    │  8 GB RAM  │          │   64 GB RAM    │
    │  200 tasks │          │   800 tasks    │
    └────────────┘          └────────────────┘
```

In Temporal terms, bump the concurrency settings:

```java
// Before
.setMaxConcurrentActivityExecutionSize(200)

// After (needs bigger machine)
.setMaxConcurrentActivityExecutionSize(800)
```

- Simpler — just one process.
- Has a hardware ceiling — you can't buy a 10,000-core machine.
- Single point of failure.
- Cost grows exponentially — big machines cost disproportionately more.

### Horizontal vs Vertical — comparison

| | Horizontal | Vertical |
|---|---|---|
| How | More machines | Bigger machine |
| Cost curve | Linear | Exponential |
| Limit | Practically unlimited | Hardware ceiling |
| Fault tolerance | ✅ one dies, others continue | ❌ single point of failure |
| Complexity | More infra to manage | Simpler — one process |

### What most teams do

**Both.** Start vertical, then go horizontal when you hit the ceiling.

```
Phase 1:  1 worker, 200 concurrency       → handles 200/sec
Phase 2:  1 worker, 500 concurrency       → handles 500/sec (bigger machine)
Phase 3:  5 workers, 500 concurrency each  → handles 2500/sec
```

---

## Concurrency

Concurrency means handling multiple tasks at the same time within a single worker process.

```
Concurrency = 1              Concurrency = 3

 Task A ██████████            Task A ██████████
 Task B     ██████████        Task B ██████████
 Task C         ██████        Task C ██████████
                              ↑ all running simultaneously
 ↑ one after another
```

Configured via `WorkerOptions`:

```java
WorkerOptions.newBuilder()
    .setMaxConcurrentWorkflowTaskExecutionSize(200)   // default 200
    .setMaxConcurrentActivityExecutionSize(200)       // default 200
    .setMaxConcurrentLocalActivityExecutionSize(200)  // default 200
    .build()
```

- **WorkflowTaskExecutionSize** — how many workflow tasks this worker handles concurrently
- **ActivityExecutionSize** — how many activities run in parallel
- **LocalActivityExecutionSize** — same but for local activities

Trade-offs:
- Set it too high → machine runs out of memory/CPU or overwhelms downstream services
- Set it too low → tasks pile up in the queue and take longer to complete

---

## Threading Model

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

**Workflows are also multi-threaded**, but each workflow task is lightweight — just replaying deterministic code (no I/O).

| | Activities | Workflows |
|---|---|---|
| Threading | 1 thread per activity execution | 1 thread per workflow task |
| Work type | Heavy (I/O, network calls) | Lightweight (decision logic, replay) |
| Why separate threads | Each call could block on network | Replay is fast, mostly CPU-bound |

200 concurrent emails = 200 threads, each independently calling `sendEmail`, each able to block on I/O without affecting the others.

---

## Kubernetes Deployment

The standard pattern is **one worker per pod, scale the number of pods**.

```
Kubernetes Deployment (replicas: 5)
┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│  Pod 1   │ │  Pod 2   │ │  Pod 3   │ │  Pod 4   │ │  Pod 5   │
│ 1 Worker │ │ 1 Worker │ │ 1 Worker │ │ 1 Worker │ │ 1 Worker │
│ 2 CPU    │ │ 2 CPU    │ │ 2 CPU    │ │ 2 CPU    │ │ 2 CPU    │
│ 4 GB     │ │ 4 GB     │ │ 4 GB     │ │ 4 GB     │ │ 4 GB     │
└─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘
     All listening on EMAIL_TASK_QUEUE
```

### Why one worker per pod

- **Kubernetes already handles horizontal scaling** — set `replicas: 5` or use HPA (Horizontal Pod Autoscaler)
- **Health checks are clean** — if the worker crashes, Kubernetes restarts that pod
- **Resource limits are predictable** — you know exactly how much one worker needs
- **Rolling updates are simpler** — pods replaced one at a time during deployments

### Example Deployment manifest

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: email-worker
spec:
  replicas: 5
  template:
    spec:
      containers:
        - name: worker
          image: email-worker:latest
          resources:
            requests:
              cpu: "2"
              memory: "4Gi"
            limits:
              cpu: "2"
              memory: "4Gi"
```

### Variable-sized pods

Rarely needed, but makes sense for **different queues with different resource needs**:

```
┌──────────────┐    ┌──────────────┐
│ Email Worker │    │ Video Worker │
│ Pod: 2 CPU   │    │ Pod: 8 CPU   │
│      4 GB    │    │      32 GB   │
│ QUEUE_EMAIL  │    │ QUEUE_VIDEO  │
└──────────────┘    └──────────────┘
  (lightweight)       (CPU-heavy)
```

These would be **separate Deployments** with different resource configs, not different-sized pods in the same Deployment.

### Summary

| Approach | When to use |
|---|---|
| 1 worker per pod, fixed size, scale replicas | 95% of cases — simple, predictable, Kubernetes-native |
| Separate Deployments with different pod sizes | Different queues with very different resource needs |
| Multiple workers in one pod | Almost never — adds complexity with no real benefit |
