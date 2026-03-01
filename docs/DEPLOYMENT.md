# AmbonMUD — Deployment Guide

This document covers building the Docker image, testing it locally, and deploying to AWS using the CDK project in `infra/`.

---

## Table of Contents

1. [Docker — Local Build & Run](#1-docker--local-build--run)
2. [CDK Infrastructure Overview](#2-cdk-infrastructure-overview)
3. [One-Time AWS Bootstrap](#3-one-time-aws-bootstrap)
4. [Deploy](#4-deploy)
5. [Topology & Tier Reference](#5-topology--tier-reference)
6. [Environment Variable Reference](#6-environment-variable-reference)
7. [CI/CD Pipeline](#7-cicd-pipeline)
8. [Operational Notes](#8-operational-notes)
9. [EC2 Single-Instance Option](#9-ec2-single-instance-option)

---

## 1. Docker — Local Build & Run

```bash
# Build the fat JAR first (the Dockerfile uses it)
./gradlew shadowJar

# Build the image
docker build -t ambonmud .

# Run STANDALONE with Docker Compose services
docker compose up -d   # start Postgres + Redis locally
docker run --rm \
  -p 4000:4000 -p 8080:8080 \
  -e AMBONMUD_DATABASE_JDBCURL=jdbc:postgresql://host.docker.internal:5432/ambonmud \
  -e AMBONMUD_DATABASE_USERNAME=ambon \
  -e AMBONMUD_DATABASE_PASSWORD=ambon \
  -e AMBONMUD_REDIS_URI=redis://host.docker.internal:6379 \
  ambonmud

# Run with no external dependencies (YAML persistence)
docker run --rm -p 4000:4000 -p 8080:8080 \
  -e AMBONMUD_PERSISTENCE_BACKEND=YAML \
  -e AMBONMUD_REDIS_ENABLED=false \
  ambonmud
```

Connect with `telnet localhost 4000` or open `http://localhost:8080` in a browser.

---

## 2. CDK Infrastructure Overview

The `infra/` directory is a TypeScript CDK project that provisions the full AWS stack:

```
Internet
  │
  ├── NLB (TCP :4000) ──────► GATEWAY Fargate tasks
  └── ALB (HTTPS :443) ─────► GATEWAY Fargate tasks
                                      │ gRPC (Cloud Map DNS)
                                      ▼
                              ENGINE Fargate tasks
                                      │
                        ┌─────────────┼─────────────┐
                        ▼             ▼             ▼
                    RDS Postgres  ElastiCache    EFS
                    (Multi-AZ)    Redis Cluster  (world_mutations)
```

**Stacks (deployed in order):**

| Stack | Purpose |
|---|---|
| `*-Vpc` | VPC, public/private/isolated subnets, security groups |
| `*-Data` | RDS Postgres 16, ElastiCache Redis 7.1, EFS, Secrets Manager |
| `*-Lb` | NLB (TCP:4000), ALB (HTTPS:443), Cloud Map namespace |
| `*-Ecs` | ECS cluster, Engine + Gateway Fargate services, auto-scaling |
| `*-Dns` | Route 53 alias records, ACM certificate (skipped if no `--context domain=`) |
| `*-Monitoring` | CloudWatch alarms, SNS topic for alerts |

---

## 3. One-Time AWS Bootstrap

**Prerequisites:** AWS CLI configured, CDK CLI installed (`npm install -g aws-cdk`).

```bash
# Bootstrap CDK in your account/region (once per account/region)
cdk bootstrap aws://ACCOUNT_ID/us-east-1

# Create two OIDC-based GitHub Actions IAM roles:
#   - github-actions-ecr-push  (ECR push only)
#   - github-actions-cdk-deploy  (CloudFormation + ECS describe/update)
# See: https://docs.github.com/en/actions/deployment/security-hardening-your-deployments/configuring-openid-connect-in-amazon-web-services

# Set GitHub repository variables:
#   AWS_ECR_PUSH_ROLE_ARN   = arn:aws:iam::ACCOUNT:role/github-actions-ecr-push
#   AWS_CDK_DEPLOY_ROLE_ARN = arn:aws:iam::ACCOUNT:role/github-actions-cdk-deploy
#   ECR_REPO_NAME           = ambonmud/app   (create this ECR repo first)
#   AWS_REGION              = us-east-1
```

**Create the ECR repository:**
```bash
aws ecr create-repository --repository-name ambonmud/app --region us-east-1
```

---

## 4. Deploy

```bash
cd infra
npm ci

# Cheapest possible (~$30-60/mo): standalone, single task, hobby tier
npx cdk deploy --all \
  --context topology=standalone \
  --context tier=hobby

# Split topology, moderate resources (~$200-500/mo)
npx cdk deploy --all \
  --context topology=split \
  --context tier=moderate \
  --context imageTag=<git-sha> \
  --context domain=play.example.com \
  --context alertEmail=ops@example.com

# Full HA production
npx cdk deploy --all \
  --context topology=split \
  --context tier=production \
  --context imageTag=<git-sha> \
  --context domain=play.example.com \
  --context alertEmail=ops@example.com
```

**Upgrade path (zero-downtime):**
```bash
# standalone/hobby → standalone/moderate: CDK resizes RDS, Redis, ECS task
npx cdk deploy --all --context topology=standalone --context tier=moderate

# standalone/* → split/*: CDK adds Engine service + Cloud Map, then Gateway
npx cdk deploy --all --context topology=split --context tier=moderate
```

**Preview changes before deploying:**
```bash
npx cdk diff --context topology=split --context tier=moderate
```

---

## 5. Topology & Tier Reference

### Topology

| | `standalone` | `split` |
|--|--|--|
| App mode | `STANDALONE` | Separate `ENGINE` + `GATEWAY` services |
| ECS services | 1 | 2 |
| Redis bus | optional | required |
| Zone sharding | ✗ | ✓ (Redis zone registry) |
| Horizontal scaling | ✗ | ✓ |

### Tier

| Dimension | `hobby` | `moderate` | `production` |
|---|---|---|---|
| AZs | 1 | 2 | 3 |
| NAT Gateways | 1 | 1 | 3 |
| RDS instance | t3.micro | t3.medium | r6g.large |
| RDS Multi-AZ | ✗ | ✓ | ✓ |
| Redis | t3.micro, 1 node | t3.small, 2 nodes | r6g.large, 3×2 |
| Engine CPU/mem (split) | 0.5 vCPU / 1 GB | 1 vCPU / 2 GB | 2 vCPU / 4 GB |
| Engine max tasks | 1 | 2 | 6 |
| Gateway CPU/mem (split) | 256 CPU / 512 MB | 0.5 vCPU / 1 GB | 1 vCPU / 2 GB |
| Gateway max tasks | 1 | 3 | 8 |
| CloudWatch alarms | basic | standard + SNS | full + SNS paging |
| Backup retention | 3 days | 7 days | 14 days |
| Estimated monthly cost | ~$30-60 | ~$200-500 | varies |

---

## 6. Environment Variable Reference

The app reads configuration from `AMBONMUD_*` environment variables at startup (Hoplite maps `SCREAMING_SNAKE_CASE` to `camelCase` config keys via lowercase + `_` → `.`).

| Variable | Config Key | Notes |
|---|---|---|
| `AMBONMUD_MODE` | `ambonMUD.mode` | `STANDALONE`, `ENGINE`, `GATEWAY` |
| `AMBONMUD_PERSISTENCE_BACKEND` | `ambonMUD.persistence.backend` | `POSTGRES` or `YAML` |
| `AMBONMUD_DATABASE_JDBCURL` | `ambonMUD.database.jdbcUrl` | Full JDBC URL |
| `AMBONMUD_DATABASE_USERNAME` | `ambonMUD.database.username` | Injected from Secrets Manager |
| `AMBONMUD_DATABASE_PASSWORD` | `ambonMUD.database.password` | Injected from Secrets Manager |
| `AMBONMUD_REDIS_ENABLED` | `ambonMUD.redis.enabled` | `true` / `false` |
| `AMBONMUD_REDIS_URI` | `ambonMUD.redis.uri` | Constructed from ElastiCache endpoint |
| `AMBONMUD_REDIS_BUS_ENABLED` | `ambonMUD.redis.bus.enabled` | Required for split topology |
| `AMBONMUD_SHARDING_ENABLED` | `ambonMUD.sharding.enabled` | Set to `true` on Engine tasks |
| `AMBONMUD_SHARDING_REGISTRY_TYPE` | `ambonMUD.sharding.registry.type` | `REDIS` for dynamic discovery |
| `AMBONMUD_SHARDING_ENGINEID` | `ambonMUD.sharding.engineId` | Auto-set by `docker-entrypoint.sh` to `$(hostname)` |
| `AMBONMUD_SHARDING_ADVERTISEHOST` | `ambonMUD.sharding.advertiseHost` | Auto-set to container private IP |
| `AMBONMUD_GRPC_CLIENT_ENGINEHOST` | `ambonMUD.grpc.client.engineHost` | `engine.internal.ambonmud` (Cloud Map) |
| `AMBONMUD_GRPC_CLIENT_ENGINEPORT` | `ambonMUD.grpc.client.enginePort` | `9090` |
| `AMBONMUD_SERVER_TELNETPORT` | `ambonMUD.server.telnetPort` | `4000` |
| `AMBONMUD_SERVER_WEBPORT` | `ambonMUD.server.webPort` | `8080` |

---

## 7. CI/CD Pipeline

**ci.yml** (on every push):
1. `test` job: `./gradlew ktlintCheck test integrationTest`
2. `frontend` job: `bun run lint && bun run build` in `web-v3/`
3. `docker` job (main branch only, after tests pass):
   - Configures AWS credentials via OIDC
   - Builds image: `docker build -t $ECR/$REPO:$SHA .`
   - Pushes `:<sha>` and `:latest` tags to ECR

**deploy.yml** (manual or triggered by CI docker job):
- `deploy-staging` job: `cdk deploy --all` targeting the staging environment
- `deploy-production` job: requires `production` GitHub environment approval gate

**Required GitHub repository variables:**
- `AWS_ECR_PUSH_ROLE_ARN` — OIDC role for ECR push (used by `ci.yml`)
- `AWS_CDK_DEPLOY_ROLE_ARN` — OIDC role for CDK deploy (used by `deploy.yml` staging)
- `AWS_CDK_DEPLOY_ROLE_ARN_PROD` — OIDC role for production CDK deploy
- `ECR_REPO_NAME` — e.g. `ambonmud/app`
- `AWS_REGION` — e.g. `us-east-1`
- `DOMAIN` — apex domain for production DNS (e.g. `example.com`)
- `ALERT_EMAIL` — SNS paging email for production alarms

---

## 8. Operational Notes

### Telnet health check

The NLB uses a TCP health check on port 4000. The Gateway must accept the TCP connection for the health check to pass. No `/healthz` endpoint is required for telnet — the NLB just needs an open TCP socket.

The ALB health check calls `GET /healthz` on port 8080 and expects HTTP 200. This endpoint is served by the Ktor web server on the Gateway (or Standalone) tasks.

### gRPC multi-target DNS (Gateway → Engine)

Cloud Map registers each Engine task as an A record under `engine.internal.ambonmud`. The Gateway's gRPC client uses this DNS name, which returns multiple A records (one per running Engine task). gRPC's default `pick_first` load-balancing policy picks the first address; for round-robin across all Engine tasks, configure `round_robin` in the gRPC channel options.

### EFS and world mutations

`data/world_mutations.yaml` is mounted from EFS at `/app/data/`. All Engine tasks share the same EFS volume so staff world-edit changes persist and are visible to all engines on next load. EFS adds ~1 ms write latency vs. local disk; acceptable for infrequent staff operations.

### Scale-in and graceful drain

ECS sends `SIGTERM` before killing tasks. The JVM shutdown hook (in `Main.kt`) calls `server.stop()`, which:
- flushes the write-coalescing player repository
- closes active sessions gracefully

`stopTimeout: 60s` on Engine tasks gives the JVM time to complete these operations. However, cross-zone handoff (migrating active players to a peer engine before shutdown) is not yet automated — this requires a custom ECS lifecycle hook or pre-stop script and is tracked as a future enhancement.

### Upgrading

CDK `cdk diff` shows exactly what will change before deploying. In-place tier upgrades (e.g. `hobby` → `moderate`) resize RDS, Redis, and ECS tasks without data loss. Topology upgrades (`standalone` → `split`) add new ECS services while draining the old one.

---

## 9. EC2 Single-Instance Option

**Use case:** always-on low-traffic server (resume showcase, demo, personal play) where the ECS/RDS/Redis overhead of other topologies isn't justified.

**Cost breakdown:**

| Component | ~Monthly |
|-----------|---------|
| EC2 t4g.nano (2 vCPU burst / 512 MB) | ~$3.07 |
| EBS gp3 8 GB (encrypted) | ~$0.64 |
| Elastic IP (free while attached) | $0 |
| Route 53 hosted zone (optional) | ~$0.50 |
| **Total** | **~$4–5/mo** |

No RDS, no Redis, no NAT gateway, no load balancer. YAML persistence stores player data at `/app/data` on the root EBS volume.

### Deploy

```bash
cd infra && npm ci

# Minimal: IP-only, no DNS
npx cdk deploy --context topology=ec2 --context imageTag=<git-sha>

# With Route 53 DNS (creates play.<domain> A record)
npx cdk deploy --context topology=ec2 --context imageTag=<git-sha> \
  --context domain=example.com
```

Stack outputs after deploy:

```
AmbonMUD-ec2.PublicIp      = 1.2.3.4
AmbonMUD-ec2.TelnetConnect = telnet 1.2.3.4 4000
AmbonMUD-ec2.WebConnect    = http://1.2.3.4:8080
AmbonMUD-ec2.SsmShell      = aws ssm start-session --target i-0abc... --region us-east-1
AmbonMUD-ec2.UpdateImage   = aws ssm send-command ... "update-ambonmud <new-tag>"
```

### Updating the running image

The instance is **not replaced** when CDK context changes — player YAML data on disk is preserved. To roll out a new image tag:

```bash
# Option A: SSM shell (interactive)
aws ssm start-session --target <instanceId> --region us-east-1
$ update-ambonmud <new-tag>

# Option B: SSM send-command (non-interactive / scriptable)
aws ssm send-command \
  --instance-ids <instanceId> \
  --document-name AWS-RunShellScript \
  --parameters 'commands=["update-ambonmud <new-tag>"]' \
  --region us-east-1
```

`update-ambonmud` does: ECR login → `docker pull` → patch service file → `systemctl restart ambonmud`.

### Shell access

No SSH key is required. Use SSM Session Manager:

```bash
aws ssm start-session --target <instanceId> --region us-east-1
```

Or open the AWS console → EC2 → Connect → Session Manager.

### Data persistence

Player YAML files live at `/app/data/players/` on the root EBS volume. They survive instance stop/start and CDK redeploys as long as the instance is not replaced (i.e., `cdk destroy` is not run and user data changes don't force replacement).

For extra safety, take periodic EBS snapshots:
```bash
aws ec2 create-snapshot --volume-id <vol-id> --description "AmbonMUD backup" --region us-east-1
```

### Upgrading to ECS Fargate later

The EC2 stack is independent of the Fargate stacks. To migrate:
1. Export player YAML files from `/app/data/` via SSM.
2. Deploy the `standalone/hobby` (or higher) Fargate topology.
3. Import player files into the new persistence backend (YAML or Postgres).
4. Run `cdk destroy --context topology=ec2` to tear down the EC2 stack.
