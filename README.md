# JDT LS JRE Container Plugin

Small Tycho project for an Eclipse plug-in that extends JDT LS so external tools can register/remove VM installs and update project JRE containers (for custom JRE containers).

## What it does
- Registers a JDT LS delegate command handler that manages VM installs and JRE containers.
- Lets external clients send commands to create/remove VM installs and enables JDT LS to resolve an arbitrary JRE container in a project's classpath.

## Commands
The plug-in contributes these JDT LS delegate commands:
- `com.github.cabutchei.jdtls.jrecontainer.createVmInstall`
  - Create or update a VM install.
  - Required: `javaHome`.
  - Optional: `vmName`, `vmId`, `vmTypeId`, `default`, `executionEnvironment`, `libraries`, `sourcePath`, `javadocUrl`.
- `com.github.cabutchei.jdtls.jrecontainer.setJreContainer`
- Update a project's JRE container.
  - Identify the project via `projectName`, `projectUri`, or `projectPath`.
  - Provide one of: `containerPath`, `executionEnvironment`, or VM info (`vmId`, `vmName`, `javaHome`).
- `com.github.cabutchei.jdtls.jrecontainer.removeVmInstall`
  - Remove a VM install by `vmId`, `vmName`, or `javaHome`.
  - Optional: `removeContainers` to remove matching JRE containers from workspace projects.

## Project layout
- `targetplatform/` - target platform definition in `targetplatform/com.github.cabutchei.jdtls.target`.
- `com.github.cabutchei.jdtls.jrecontainer/` - the Eclipse plug-in implementation.

## Build
```bash
mvn clean verify
```

## Install
- Take the built plug-in JAR from `com.github.cabutchei.jdtls.jrecontainer/target/` and place it in your JDT LS `plugins/` directory, or contribute it to the server via the `javaExtensions`property.
- Requires Java 17 (per `Bundle-RequiredExecutionEnvironment`).
