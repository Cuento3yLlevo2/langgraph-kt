# Security Policy

## Supported versions

langgraph-kt is pre-1.0. Security fixes are released for the latest published version only.

## Reporting a vulnerability

Please do not open a public issue for security problems.

Report them privately through GitHub: open the repository's **Security** tab and choose
**Report a vulnerability**
([direct link](https://github.com/Cuento3yLlevo2/langgraph-kt/security/advisories/new)).
Include the affected version, a description of the problem and, if you can, a minimal way to
reproduce it.

You can expect an acknowledgement within a week. Once a fix is released, the advisory is published
and you are credited unless you prefer otherwise.

## Scope

Areas where reports are especially useful:

- `FileCheckpointer`: thread ids are mapped to file names, so anything that lets an id read or write
  outside the checkpoint directory is a vulnerability.
- Checkpoint loading: stored state is deserialized with the `StateSerializer` you provide. Do not
  load checkpoints from sources you do not trust.

Prompt injection and the behaviour of the language models you call through this library are outside
its scope.
