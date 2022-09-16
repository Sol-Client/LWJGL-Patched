package io.github.solclient.lwjgl;

import org.apache.commons.io.IOUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class PatchTask extends DefaultTask {

	private final File outputFile;
	private final File coreFile;
	private final File stbFile;

	public PatchTask() {
		File libs = new File(getProject().getBuildDir(), "libs");
		outputFile = new File(libs, "lwjgl-patched-all.jar");
		coreFile = new File(libs, "lwjgl-core.jar");
		stbFile = new File(libs, "lwjgl-stb.jar");
	}

	private static void combine(File outputFile, File... files) throws IOException {
		ZipFile[] zips = new ZipFile[files.length];

		for(int index = 0; index < files.length; index++) {
			zips[index] = new ZipFile(files[index]);
		}

		Set<String> seenNames = new HashSet<>();

		try(ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outputFile))) {
			for(ZipFile zip : zips) {
				for(ZipEntry entry : Collections.list(zip.entries())) {
					if(entry.isDirectory() || !seenNames.add(entry.getName())) {
						continue;
					}

					out.putNextEntry(new ZipEntry(entry.getName()));
					InputStream entryIn = zip.getInputStream(entry);
					IOUtils.copy(entryIn, out);
					entryIn.close();
				}
				zip.close();
			}
		}
	}

	private static void process(File inFile, File outFile) throws IOException {
		try(ZipFile in = new ZipFile(inFile); ZipOutputStream out = new ZipOutputStream(new FileOutputStream(outFile))) {
			for(ZipEntry entry : Collections.list(in.entries())) {
				if(entry.isDirectory()) {
					continue;
				}
				// just like with the old mods
				if(entry.getName().startsWith("META-INF/")) {
					continue;
				}

				InputStream entryIn = in.getInputStream(entry);
				if(entry.getName().endsWith(".class")) {
					byte[] data = IOUtils.toByteArray(entryIn);
					ClassReader reader = new ClassReader(data);
					ClassWriter writer = new ClassWriter(0);

					reader.accept(new ClassRemapper(writer, new Remapper() {

						@Override
						public String map(String internalName) {
							return replace(internalName);
						}

					}), 0);
					data = writer.toByteArray();
					out.putNextEntry(new ZipEntry(replace(entry.getName().substring(0, entry.getName().lastIndexOf('.'))) + ".class"));
					out.write(data);
				}
				else {
					out.putNextEntry(new ZipEntry(entry.getName()));
					IOUtils.copy(entryIn, out);
				}
				entryIn.close();
			}
		}
	}

	private static String replace(String string) {
		if(string.endsWith("org/lwjgl/BufferUtils")
				|| string.endsWith("org/lwjgl/PointerBuffer")) {
			return string.replace("lwjgl", "lwjgl3");
		}
		return string;
	}

	private static File getDependency(Set<File> deps, String prefix) { // quick-n-dirty
		return deps.stream().filter((dep) -> dep.getName().substring(0, dep.getName().lastIndexOf('-')).equals(prefix)).findFirst().get();
	}

	@TaskAction
	public void run() throws IOException {
		outputFile.getParentFile().mkdirs();
		Set<File> deps = getProject().getConfigurations().getByName("lwjgl").resolve();
		process(getDependency(deps, "lwjgl"), coreFile);
		process(getDependency(deps, "lwjgl-stb"), stbFile);
		combine(outputFile, coreFile, stbFile);
	}

}
