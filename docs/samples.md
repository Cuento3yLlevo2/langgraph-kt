# Samples

Runnable examples live in the repository, in [`samples/`](https://github.com/deeptelar/telar/tree/main/samples/src/main/kotlin/dev/deeptelar/telar/samples):

```bash
./gradlew :samples:runQuickStart        # the email support agent of the quick start
./gradlew :samples:runDecisionRouter    # the same agent, routed by a decision model
./gradlew :samples:runHumanInTheLoop    # a refund that waits for approval, saved to disk
./gradlew :samples:runReviewLoop        # a reviewer approves a draft or sends it back
./gradlew :samples:runAskFromANode      # a payout that asks for approval only when it is large
./gradlew :samples:runSubgraph          # the payout graph as one node of a larger graph
./gradlew :samples:runParallelResearch  # three lookups at the same time
./gradlew :samples:runChatAgent         # a chat agent built on a LangChain4j model
./gradlew :samples:runToolAgent         # an agent that calls tools, with or without an API key
./gradlew :samples:runLevel1            # ... runLevel8, the levels of the tutorial
```

For a complete app, see [telar-demo](https://github.com/deeptelar/telar-demo),
the source of the [Pixel Pizza game](https://deeptelar.github.io/telar-demo/). It is a
Compose Multiplatform app for the browser (Kotlin/Wasm), the desktop and Android, and it uses this
library from Maven Central.
