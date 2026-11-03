# heroku-ops

Watches the production Heroku apps of the [factory](https://github.com/jamesward/projects): apps send it logs, it detects incidents with heuristics, and it fires the app's Claude Code routine, which opens an issue with the evidence and may propose a fix for the owner to approve.

The design is in the factory's [DESIGN.md](https://github.com/jamesward/projects/blob/main/DESIGN.md) and the plan in its [TODO.md](https://github.com/jamesward/projects/blob/main/TODO.md).

```
OPS_SECRET=dev PORT=8080 ./sbta run
curl localhost:8080/health
```
