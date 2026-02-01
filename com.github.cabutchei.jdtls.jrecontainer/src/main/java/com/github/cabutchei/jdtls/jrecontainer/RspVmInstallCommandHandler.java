package com.github.cabutchei.jdtls.jrecontainer;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.launching.StandardVMType;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.launching.LibraryLocation;
import org.eclipse.jdt.launching.VMStandin;
import org.eclipse.jdt.launching.environments.IExecutionEnvironment;
import org.eclipse.jdt.launching.environments.IExecutionEnvironmentsManager;
import org.eclipse.jdt.ls.core.internal.IDelegateCommandHandler;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;

public class RspVmInstallCommandHandler implements IDelegateCommandHandler {

	public static final String COMMAND_ID = "com.github.cabutchei.jdtls.jrecontainer.createVmInstall";
	public static final String COMMAND_ID_SET_JRE_CONTAINER = "com.github.cabutchei.jdtls.jrecontainer.setJreContainer";
	public static final String COMMAND_ID_REMOVE_VM = "com.github.cabutchei.jdtls.jrecontainer.removeVmInstall";
	private static final String DEBUG_UI_VM_TYPE_ID = "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType";
	private static final String CORE_VM_TYPE_ID = StandardVMType.ID_STANDARD_VM_TYPE;

	@Override
	public Object executeCommand(String commandId, List<Object> arguments, IProgressMonitor monitor) throws Exception {
		if (COMMAND_ID_SET_JRE_CONTAINER.equals(commandId)) {
			return handleSetJreContainer(arguments, monitor);
		}
		if (COMMAND_ID_REMOVE_VM.equals(commandId)) {
			return handleRemoveVmInstall(arguments, monitor);
		}
		if (!COMMAND_ID.equals(commandId)) {
			return result(false, "Unsupported command: " + commandId, null);
		}

		Request request = Request.from(arguments);
		if (request == null || request.javaHome == null) {
			return result(false, "Missing javaHome in request.", null);
		}

		File installLocation = new File(request.javaHome);
		if (!installLocation.isDirectory()) {
			return result(false, "javaHome does not point to a directory: " + request.javaHome, null);
		}

		String requestedTypeId = request.vmTypeId != null ? request.vmTypeId : StandardVMType.ID_STANDARD_VM_TYPE;
		IVMInstallType installType = JavaRuntime.getVMInstallType(requestedTypeId);
		if (installType == null) {
			return result(false, "VM install type not available: " + requestedTypeId, null);
		}

		IStatus validation = installType.validateInstallLocation(installLocation);
		if (!validation.isOK()) {
			return result(false, validation.getMessage(), null);
		}

		String vmName = request.vmName;
		if (vmName == null || vmName.isBlank()) {
			vmName = installLocation.getName();
		}

		String resolvedTypeId = installType.getId();
		IVMInstall existingById = resolveVmByIdAndType(request.vmId, resolvedTypeId);
		IVMInstall existing = existingById != null ? existingById : findVM(installLocation, vmName);
		if (existing != null && !resolvedTypeId.equals(existing.getVMInstallType().getId())) {
			existing = null;
		}
		String vmId = request.vmId;
		if (existingById == null && vmId != null && !vmId.isBlank()) {
			IVMInstall idConflict = findVMById(vmId);
			if (idConflict != null && !resolvedTypeId.equals(idConflict.getVMInstallType().getId())) {
				vmId = null;
			}
		}
		VMStandin standin = existing == null
				? new VMStandin(installType, vmId != null && !vmId.isBlank() ? vmId : createUniqueId(installType))
				: new VMStandin(existing);
		standin.setName(vmName);
		standin.setInstallLocation(installLocation);

		if (request.libraries != null && !request.libraries.isEmpty()) {
			standin.setLibraryLocations(createLibraryLocations(request.libraries, request.sourcePath, request.javadocUrl));
		} else if (request.sourcePath != null || request.javadocUrl != null) {
			LibraryLocation[] current = existing != null ? existing.getLibraryLocations() : null;
			if (current != null && current.length > 0) {
				standin.setLibraryLocations(applySourceAndJavadoc(current, request.sourcePath, request.javadocUrl));
			}
		}

		IVMInstall vm = standin.convertToRealVM();
		if (request.defaultVm) {
			JavaRuntime.setDefaultVMInstall(vm, monitor);
		}

		String envName = request.executionEnvironment != null ? request.executionEnvironment : request.name;
		if (envName != null && !envName.isBlank()) {
			boolean envSet = setDefaultEnvironmentVM(vm, envName);
			if (!envSet) {
				JavaLanguageServerPlugin.logInfo("VM install is not compatible with execution environment: " + envName);
			}
		}

		JavaRuntime.saveVMConfiguration();
		Map<String, Object> payload = new HashMap<>();
		payload.put("id", vm.getId());
		payload.put("name", vm.getName());
		payload.put("path", vm.getInstallLocation().getAbsolutePath());
		return result(true, "VM install configured.", payload);
	}

	private Object handleRemoveVmInstall(List<Object> arguments, IProgressMonitor monitor) throws Exception {
		RemoveVmRequest request = RemoveVmRequest.from(arguments);
		if (request == null) {
			return result(false, "Missing parameters for removeVmInstall.", null);
		}
		IVMInstall vm = resolveVmInstall(request);
		if (vm == null) {
			return result(false, "VM install not found.", null);
		}
		IPath vmContainerPath = JavaRuntime.newJREContainerPath(vm);
		boolean wasDefault = Objects.equals(vm, JavaRuntime.getDefaultVMInstall());
		removeVmInstall(vm);
		if (wasDefault) {
			IVMInstall replacement = findAnyVmInstall();
			if (replacement != null) {
				JavaRuntime.setDefaultVMInstall(replacement, monitor);
			}
		}
		int removedContainers = 0;
		if (request.removeContainers) {
			removedContainers = removeJreContainers(vmContainerPath, monitor);
		}
		JavaRuntime.saveVMConfiguration();
		Map<String, Object> payload = new HashMap<>();
		payload.put("id", vm.getId());
		payload.put("name", vm.getName());
		payload.put("path", vm.getInstallLocation().getAbsolutePath());
		payload.put("removedContainers", Integer.valueOf(removedContainers));
		return result(true, "VM install removed.", payload);
	}

	private Object handleSetJreContainer(List<Object> arguments, IProgressMonitor monitor) throws Exception {
		JreContainerRequest request = JreContainerRequest.from(arguments);
		if (request == null) {
			return result(false, "Missing parameters for setJreContainer.", null);
		}
		IProject project = resolveProject(request);
		if (project == null || !project.exists()) {
			return result(false, "Project not found.", null);
		}
		IJavaProject javaProject = JavaCore.create(project);
		if (javaProject == null || !javaProject.exists()) {
			return result(false, "Project is not a Java project.", null);
		}

		IPath containerPath = resolveContainerPath(request);
		if (containerPath == null) {
			return result(false, "Unable to resolve JRE container path from request.", null);
		}

		IClasspathEntry newEntry = JavaCore.newContainerEntry(containerPath);
		IClasspathEntry[] raw = javaProject.getRawClasspath();
		List<IClasspathEntry> updated = new ArrayList<>();
		boolean replaced = false;
		for (IClasspathEntry entry : raw) {
			if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
					&& JavaRuntime.JRE_CONTAINER.equals(entry.getPath().segment(0))) {
				if (!replaced) {
					updated.add(newEntry);
					replaced = true;
				}
			} else {
				updated.add(entry);
			}
		}
		if (!replaced) {
			updated.add(newEntry);
		}
		javaProject.setRawClasspath(updated.toArray(new IClasspathEntry[0]), monitor);
		IVMInstall resolvedVm = JavaRuntime.getVMInstall(containerPath);
		String vmTypeId = JavaRuntime.getVMInstallTypeId(containerPath);
		String vmName = JavaRuntime.getVMInstallName(containerPath);
		List<String> vmNames = listVmNames(vmTypeId);
		Map<String, Object> payload = new HashMap<>();
		payload.put("project", project.getName());
		payload.put("containerPath", containerPath.toString());
		payload.put("resolvedVmName", resolvedVm == null ? null : resolvedVm.getName());
		payload.put("resolvedVmId", resolvedVm == null ? null : resolvedVm.getId());
		payload.put("vmTypeId", vmTypeId);
		payload.put("vmName", vmName);
		payload.put("vmNamesForType", vmNames);
		return result(true, "JRE container updated.", payload);
	}

	private static IProject resolveProject(JreContainerRequest request) {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		if (request.projectName != null && !request.projectName.isBlank()) {
			IProject project = root.getProject(request.projectName);
			return project.exists() ? project : null;
		}
		if (request.projectUri != null && !request.projectUri.isBlank()) {
			IProject fromUri = findProjectByUri(root, request.projectUri);
			if (fromUri != null) {
				return fromUri;
			}
		}
		if (request.projectPath != null && !request.projectPath.isBlank()) {
			IProject fromPath = findProjectByUri(root, request.projectPath);
			if (fromPath != null) {
				return fromPath;
			}
		}
		return null;
	}

	private static IProject findProjectByUri(IWorkspaceRoot root, String value) {
		try {
			URI uri = value.contains("://") ? URI.create(value) : new File(value).toURI();
			IContainer[] containers = root.findContainersForLocationURI(uri);
			for (IContainer container : containers) {
				IProject project = container.getProject();
				if (project != null && project.exists()) {
					return project;
				}
			}
		} catch (IllegalArgumentException e) {
			return null;
		}
		return null;
	}

	private static IPath resolveContainerPath(JreContainerRequest request) {
		if (request.containerPath != null && !request.containerPath.isBlank()) {
			// return new Path(request.containerPath);
			return normalizeContainerPath(new Path(request.containerPath));
		}
		if (request.executionEnvironment != null && !request.executionEnvironment.isBlank()) {
			IExecutionEnvironment env = getExecutionEnvironment(request.executionEnvironment);
			if (env != null) {
				return JavaRuntime.newJREContainerPath(env);
			}
		}
		IVMInstall vm = resolveVmInstall(request);
		if (vm != null) {
			// return JavaRuntime.newJREContainerPath(vm);
			return normalizeContainerPath(JavaRuntime.newJREContainerPath(vm));
		}
		return null;
	}

	private static IPath normalizeContainerPath(IPath path) {
		if (path == null || path.segmentCount() < 2) {
			return path;
		}
		if (!JavaRuntime.JRE_CONTAINER.equals(path.segment(0))) {
			return path;
		}
		if (DEBUG_UI_VM_TYPE_ID.equals(path.segment(1))) {
			IPath remainder = path.removeFirstSegments(2);
			return new Path(JavaRuntime.JRE_CONTAINER).append(CORE_VM_TYPE_ID).append(remainder);
		}
		return path;
	}

	private static IVMInstall resolveVmInstall(JreContainerRequest request) {
		if (request.vmId != null && !request.vmId.isBlank()) {
			IVMInstall byId = findVMById(request.vmId);
			if (byId != null) {
				return byId;
			}
		}
		if (request.javaHome != null && !request.javaHome.isBlank()) {
			return findVM(new File(request.javaHome), request.vmName);
		}
		if (request.vmName != null && !request.vmName.isBlank()) {
			return findVM(null, request.vmName);
		}
		return null;
	}

	private static IVMInstall resolveVmInstall(RemoveVmRequest request) {
		if (request.vmId != null && !request.vmId.isBlank()) {
			IVMInstall byId = findVMById(request.vmId);
			if (byId != null) {
				return byId;
			}
		}
		if (request.javaHome != null && !request.javaHome.isBlank()) {
			return findVM(new File(request.javaHome), request.vmName);
		}
		if (request.vmName != null && !request.vmName.isBlank()) {
			return findVM(null, request.vmName);
		}
		return null;
	}

	private static IVMInstall findVMById(String vmId) {
		IVMInstallType[] types = JavaRuntime.getVMInstallTypes();
		for (IVMInstallType type : types) {
			IVMInstall candidate = type.findVMInstall(vmId);
			if (candidate != null) {
				return candidate;
			}
		}
		return null;
	}

	private static IVMInstall resolveVmByIdAndType(String vmId, String expectedTypeId) {
		if (vmId == null || vmId.isBlank() || expectedTypeId == null) {
			return null;
		}
		IVMInstall candidate = findVMById(vmId);
		if (candidate == null) {
			return null;
		}
		String candidateTypeId = candidate.getVMInstallType().getId();
		return expectedTypeId.equals(candidateTypeId) ? candidate : null;
	}

	private static IVMInstall findAnyVmInstall() {
		IVMInstallType[] types = JavaRuntime.getVMInstallTypes();
		for (IVMInstallType type : types) {
			IVMInstall[] installs = type.getVMInstalls();
			if (installs != null && installs.length > 0) {
				return installs[0];
			}
		}
		return null;
	}
	private static List<String> listVmNames(String vmTypeId) {
		if (vmTypeId == null) {
			return Collections.emptyList();
		}
		IVMInstallType type = JavaRuntime.getVMInstallType(vmTypeId);
		if (type == null) {
			return Collections.emptyList();
		}
		IVMInstall[] installs = type.getVMInstalls();
		if (installs == null || installs.length == 0) {
			return Collections.emptyList();
		}
		List<String> names = new ArrayList<>();
		for (IVMInstall install : installs) {
			names.add(install.getName());
		}
		return names;
	}

	private static void removeVmInstall(IVMInstall vm) {
		IVMInstallType type = vm.getVMInstallType();
		String id = vm.getId();
		type.disposeVMInstall(id);
	}

	private static int removeJreContainers(IPath containerPath, IProgressMonitor monitor) throws Exception {
		if (containerPath == null) {
			return 0;
		}
		int removed = 0;
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IProject[] projects = root.getProjects();
		for (IProject project : projects) {
			if (!project.exists()) {
				continue;
			}
			IJavaProject javaProject = JavaCore.create(project);
			if (javaProject == null || !javaProject.exists()) {
				continue;
			}
			IClasspathEntry[] raw = javaProject.getRawClasspath();
			List<IClasspathEntry> updated = new ArrayList<>();
			boolean changed = false;
			for (IClasspathEntry entry : raw) {
				if (entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
						&& containerPath.equals(entry.getPath())) {
					changed = true;
					removed++;
				} else {
					updated.add(entry);
				}
			}
			if (changed) {
				javaProject.setRawClasspath(updated.toArray(new IClasspathEntry[0]), monitor);
			}
		}
		return removed;
	}

	private static LibraryLocation[] createLibraryLocations(List<String> libraries, String sourcePath, String javadocUrl) {
		List<LibraryLocation> result = new ArrayList<>();
		IPath source = sourcePath != null ? new Path(sourcePath) : null;
		URL javadoc = toUrl(javadocUrl);
		for (String lib : libraries) {
			if (lib == null || lib.isBlank()) {
				continue;
			}
			IPath libPath = new Path(lib);
			LibraryLocation location = new LibraryLocation(libPath, source, Path.EMPTY, javadoc, null, null);
			result.add(location);
		}
		return result.toArray(new LibraryLocation[0]);
	}

	private static LibraryLocation[] applySourceAndJavadoc(LibraryLocation[] libs, String sourcePath, String javadocUrl) {
		IPath source = sourcePath != null ? new Path(sourcePath) : null;
		URL javadoc = toUrl(javadocUrl);
		LibraryLocation[] updated = new LibraryLocation[libs.length];
		for (int i = 0; i < libs.length; i++) {
			LibraryLocation lib = libs[i];
			IPath systemSource = source != null ? source : lib.getSystemLibrarySourcePath();
			URL newJavadoc = javadoc != null ? javadoc : lib.getJavadocLocation();
			updated[i] = new LibraryLocation(lib.getSystemLibraryPath(), systemSource, lib.getPackageRootPath(),
					newJavadoc, lib.getIndexLocation(), lib.getExternalAnnotationsPath());
		}
		return updated;
	}

	private static URL toUrl(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return new URL(value);
		} catch (MalformedURLException e) {
			return null;
		}
	}

	private static String createUniqueId(IVMInstallType installType) {
		long unique = System.currentTimeMillis();
		while (installType.findVMInstall(String.valueOf(unique)) != null) {
			unique++;
		}
		return String.valueOf(unique);
	}

	private static IVMInstall findVM(File installLocation, String name) {
		IVMInstallType[] types = JavaRuntime.getVMInstallTypes();
		for (IVMInstallType type : types) {
			for (IVMInstall install : type.getVMInstalls()) {
				if (name != null && Objects.equals(name, install.getName())) {
					return install;
				}
				if (installLocation != null && Objects.equals(installLocation, install.getInstallLocation())) {
					return install;
				}
			}
		}
		return null;
	}

	private static boolean setDefaultEnvironmentVM(IVMInstall vm, String environmentName) {
		IExecutionEnvironment environment = getExecutionEnvironment(environmentName);
		if (environment == null) {
			return false;
		}
		if (Objects.equals(vm, environment.getDefaultVM())) {
			return true;
		}
		for (IVMInstall candidate : environment.getCompatibleVMs()) {
			if (candidate.equals(vm)) {
				environment.setDefaultVM(candidate);
				return true;
			}
		}
		return false;
	}

	private static IExecutionEnvironment getExecutionEnvironment(String name) {
		IExecutionEnvironmentsManager manager = JavaRuntime.getExecutionEnvironmentsManager();
		for (IExecutionEnvironment environment : manager.getExecutionEnvironments()) {
			if (environment.getId().equals(name)) {
				return environment;
			}
		}
		return null;
	}

	private static Map<String, Object> result(boolean ok, String message, Map<String, Object> payload) {
		Map<String, Object> result = new HashMap<>();
		result.put("ok", Boolean.valueOf(ok));
		result.put("message", message);
		if (payload != null) {
			result.putAll(payload);
		}
		return result;
	}

	private static final class Request {
		private final String javaHome;
		private final String name;
		private final String vmName;
		private final String executionEnvironment;
		private final String vmTypeId;
		private final String vmId;
		private final boolean defaultVm;
		private final List<String> libraries;
		private final String sourcePath;
		private final String javadocUrl;

		private Request(String javaHome, String name, String vmName, String executionEnvironment, String vmTypeId,
				String vmId, boolean defaultVm, List<String> libraries, String sourcePath, String javadocUrl) {
			this.javaHome = javaHome;
			this.name = name;
			this.vmName = vmName;
			this.executionEnvironment = executionEnvironment;
			this.vmTypeId = vmTypeId;
			this.vmId = vmId;
			this.defaultVm = defaultVm;
			this.libraries = libraries;
			this.sourcePath = sourcePath;
			this.javadocUrl = javadocUrl;
		}

		static Request from(List<Object> arguments) {
			if (arguments == null || arguments.isEmpty()) {
				return null;
			}
			Object first = arguments.get(0);
			if (first instanceof Map<?, ?>) {
				return fromMap((Map<?, ?>) first);
			}
			if (first instanceof String) {
				String javaHome = (String) first;
				String env = arguments.size() > 1 && arguments.get(1) instanceof String ? (String) arguments.get(1) : null;
				return new Request(javaHome, env, null, env, null, null, false, Collections.emptyList(), null, null);
			}
			return null;
		}

		private static Request fromMap(Map<?, ?> raw) {
			String javaHome = firstString(raw, "javaHome", "path", "installPath");
			String name = firstString(raw, "name", "executionEnvironment");
			String vmName = firstString(raw, "vmName", "vmInstallName");
			String env = firstString(raw, "executionEnvironment", "environment", "name");
			String vmTypeId = firstString(raw, "vmTypeId", "vmInstallType", "vmType");
			String vmId = firstString(raw, "vmId", "vmInstallId", "id");
			boolean def = firstBoolean(raw, "default", "setDefault");
			List<String> libs = firstStringList(raw, "libraries", "libPaths");
			String source = firstString(raw, "sourcePath", "source");
			String javadoc = firstString(raw, "javadoc", "javadocUrl");
			return new Request(javaHome, name, vmName, env, vmTypeId, vmId, def, libs, source, javadoc);
		}

		private static String firstString(Map<?, ?> raw, String... keys) {
			for (String key : keys) {
				Object value = raw.get(key);
				if (value instanceof String && !((String) value).isBlank()) {
					return (String) value;
				}
			}
			return null;
		}

		private static boolean firstBoolean(Map<?, ?> raw, String... keys) {
			for (String key : keys) {
				Object value = raw.get(key);
				if (value instanceof Boolean) {
					return ((Boolean) value).booleanValue();
				}
				if (value instanceof String) {
					return Boolean.parseBoolean((String) value);
				}
			}
			return false;
		}

		private static List<String> firstStringList(Map<?, ?> raw, String... keys) {
			for (String key : keys) {
				Object value = raw.get(key);
				if (value instanceof List<?>) {
					List<?> list = (List<?>) value;
					List<String> result = new ArrayList<>();
					for (Object item : list) {
						if (item instanceof String && !((String) item).isBlank()) {
							result.add((String) item);
						}
					}
					return result;
				}
			}
			return Collections.emptyList();
		}
	}

	private static final class JreContainerRequest {
		private final String projectName;
		private final String projectUri;
		private final String projectPath;
		private final String executionEnvironment;
		private final String containerPath;
		private final String vmId;
		private final String vmName;
		private final String javaHome;

		private JreContainerRequest(String projectName, String projectUri, String projectPath, String executionEnvironment,
				String containerPath, String vmId, String vmName, String javaHome) {
			this.projectName = projectName;
			this.projectUri = projectUri;
			this.projectPath = projectPath;
			this.executionEnvironment = executionEnvironment;
			this.containerPath = containerPath;
			this.vmId = vmId;
			this.vmName = vmName;
			this.javaHome = javaHome;
		}

		static JreContainerRequest from(List<Object> arguments) {
			if (arguments == null || arguments.isEmpty()) {
				return null;
			}
			Object first = arguments.get(0);
			if (first instanceof Map<?, ?>) {
				return fromMap((Map<?, ?>) first);
			}
			if (first instanceof String) {
				String projectUri = (String) first;
				String executionEnvironment = arguments.size() > 1 && arguments.get(1) instanceof String ? (String) arguments.get(1) : null;
				return new JreContainerRequest(null, projectUri, null, executionEnvironment, null, null, null, null);
			}
			return null;
		}

		private static JreContainerRequest fromMap(Map<?, ?> raw) {
			String projectName = Request.firstString(raw, "projectName", "name");
			String projectUri = Request.firstString(raw, "projectUri", "projectURI", "uri");
			String projectPath = Request.firstString(raw, "projectPath", "path");
			String executionEnvironment = Request.firstString(raw, "executionEnvironment", "environment", "ee");
			String containerPath = Request.firstString(raw, "containerPath");
			String vmId = Request.firstString(raw, "vmId", "vmInstallId");
			String vmName = Request.firstString(raw, "vmName", "vmInstallName");
			String javaHome = Request.firstString(raw, "javaHome");
			return new JreContainerRequest(projectName, projectUri, projectPath, executionEnvironment, containerPath, vmId, vmName, javaHome);
		}
	}

	private static final class RemoveVmRequest {
		private final String vmId;
		private final String vmName;
		private final String javaHome;
		private final boolean removeContainers;

		private RemoveVmRequest(String vmId, String vmName, String javaHome, boolean removeContainers) {
			this.vmId = vmId;
			this.vmName = vmName;
			this.javaHome = javaHome;
			this.removeContainers = removeContainers;
		}

		static RemoveVmRequest from(List<Object> arguments) {
			if (arguments == null || arguments.isEmpty()) {
				return null;
			}
			Object first = arguments.get(0);
			if (first instanceof Map<?, ?>) {
				return fromMap((Map<?, ?>) first);
			}
			if (first instanceof String) {
				String vmId = (String) first;
				String javaHome = arguments.size() > 1 && arguments.get(1) instanceof String ? (String) arguments.get(1) : null;
				return new RemoveVmRequest(vmId, null, javaHome, false);
			}
			return null;
		}

		private static RemoveVmRequest fromMap(Map<?, ?> raw) {
			String vmId = Request.firstString(raw, "vmId", "id");
			String vmName = Request.firstString(raw, "vmName", "name");
			String javaHome = Request.firstString(raw, "javaHome", "path");
			boolean removeContainers = Request.firstBoolean(raw, "removeContainers", "removeJreContainers", "removeContainersInProjects");
			return new RemoveVmRequest(vmId, vmName, javaHome, removeContainers);
		}
	}
}
