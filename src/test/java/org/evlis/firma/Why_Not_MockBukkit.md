# Why We're Not Using MockBukkit

## Version Compatibility Chaos

- **No version labeling** - 166+ versions with NO clear mapping to Paper API versions; MockBukkit releases don't clearly indicate which API they support, resulting in `IncompatiblePaperVersionException`'s - `mockbukkit-v1.21:4.110.0` threw version incompatibility errors with Paper 1.21.4-R0.1-SNAPSHOT
- **Trial and error required** - Must test multiple versions (4.110.0, 4.96.0, etc.) to find one that works with your specific Paper version

## Build System Complexity

- **Gradle Kotlin DSL incompatibility** - The README's manifest extraction pattern is written for Groovy and doesn't translate to Kotlin DSL
  - **Configuration resolution errors** - `configurations.testImplementation.get().resolve()` fails in modern Gradle (not a resolvable configuration)
  - **Import issues** - `java.util.jar.JarFile` requires explicit imports in `.kts` files, causing compilation errors
  - **Type inference problems** - Kotlin DSL requires explicit type annotations that the Groovy examples don't show

## Project Instability

- **Broken publish workflow** - The badges reference `publish.yml` but the actual build status is unclear
- **Maven Central confusion** - Documentation references https://central.sonatype.com/artifact/org.mockbukkit.mockbukkit/mockbukkit-v26.1 - which returns a 404 error, actual maven is https://mvnrepository.com/artifact/org.mockbukkit.mockbukkit
- **Branch maintenance disclaimer** - README admits backport branches "will not be receiving patches actively" (We're on 1.21.4, no clear status or maintenance plan)
- **UnimplementedOperationExceptions** -  Tests get skipped instead of failing when methods aren't implemented, giving false confidence

## Our Decision

For this project, we're ONLY writing unit tests for Pure Java classes and methods where possible. All other tests will unfortunately have to be done at runtime via runServer().
