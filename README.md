# heroku-ops

Watches the production Heroku apps of the [factory](https://github.com/jamesward/projects), investigates their incidents with an AI agent, and fixes them within risk rules: a low-risk fix merges after CI passes, and everything else waits for the owner's approval.

This repo is a skeleton so far: a server with `GET /health`. The design is in the factory's [DESIGN.md](https://github.com/jamesward/projects/blob/main/DESIGN.md) and the plan in its [TODO.md](https://github.com/jamesward/projects/blob/main/TODO.md).

```
PORT=8080 ./sbta run
curl localhost:8080/health
```
