
## google-helpers (JADX plugin)

Helpers for reverse engineering Google apps in JADX.
Renames classes in Google APKs based on log strings.

- Homepage: https://github.com/benwaffle/jadx-google-helpers
- Install location id: `github:benwaffle:jadx-google-helpers`

In jadx-cli:
```bash
jadx plugins --install "github:benwaffle:jadx-google-helpers"
```

Options (pass with `-P<name>=<value>`):
- `google-helpers.targetClass`: class to process, e.g. `a/b/C` or `a.b.C` (required for now)
- `google-helpers.factoryMethodRef`: method ref like `com/google/common/flogger/GoogleLogger->c(Ljava/lang/String;)Lcom/google/common/flogger/GoogleLogger;`
- `google-helpers.locationMethodRef`: method ref like `x/y/AnotherLogger->setLocation(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;)V`

Example:
```bash
jadx -Pgoogle-helpers.targetClass=a/b/C \
     -Pgoogle-helpers.factoryMethodRef=com/google/common/flogger/GoogleLogger->c\(Ljava/lang/String;\)Lcom/google/common/flogger/GoogleLogger; \
     -Pgoogle-helpers.locationMethodRef=obf/p/Q->a\(Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;\)V \
     -d out app.apk
```
