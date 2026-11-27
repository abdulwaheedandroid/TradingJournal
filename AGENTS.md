# Trading Journal engineering principles

- Apply SOLID where it clarifies responsibilities and dependencies. Keep trade calculations independent of Compose and Room. Keep UI state and file formats out of the database schema. Introduce interfaces when callers need a meaningful boundary or multiple implementations.
- Apply KISS. Prefer direct, readable code, explicit transaction boundaries, and a small number of moving parts. Avoid blocking the UI thread with database or file I/O.
- Apply DRY to shared business rules and data contracts. Consolidate duplicated logic when it has the same reason to change. Keep historical migration definitions independent of current schema definitions.
- Apply YAGNI. Add only the features, abstractions, dependencies, and configuration needed for today's requirements.
- Preserve financial precision, existing records, and backup compatibility. Keep related writes atomic and verify affected workflows with focused regression tests and the Gradle build.
