/*
 * Copyright (c) 2016-2017, Excelsior LLC.
 *
 *  This file is part of Excelsior JET API.
 *
 *  Excelsior JET API is free software:
 *  you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Excelsior JET API is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Excelsior JET API.
 *  If not, see <http://www.gnu.org/licenses/>.
 *
*/
package com.excelsiorjet.api.tasks;

import com.excelsiorjet.api.ExcelsiorJet;
import com.excelsiorjet.api.cmd.CmdLineTool;
import com.excelsiorjet.api.cmd.CmdLineToolException;
import com.excelsiorjet.api.tasks.config.ApplicationType;
import com.excelsiorjet.api.tasks.config.PackagingType;
import com.excelsiorjet.api.tasks.config.compiler.ExecProfilesConfig;
import com.excelsiorjet.api.util.Txt;
import com.excelsiorjet.api.util.Utils;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static com.excelsiorjet.api.log.Log.logger;
import static com.excelsiorjet.api.util.Txt.s;

/**
 * Task for building Java (JVM) applications with Excelsior JET.
 *
 * @author Nikita Lipsky
 * @author Aleksey Zhidkov
 */
public class JetBuildTask {

    private final JetProject project;
    private final CompilerArgsGenerator compilerArgsGenerator;
    private final PackagerArgsGenerator packagerArgsGenerator;
    private final ExcelsiorJet excelsiorJet;
    private final boolean toProfile;

    private File buildDir;

    public JetBuildTask(ExcelsiorJet excelsiorJet, JetProject project, boolean profile) throws JetTaskFailureException {
        this.excelsiorJet = excelsiorJet;
        this.project = project;
        this.toProfile = profile;
        compilerArgsGenerator = new CompilerArgsGenerator(project, excelsiorJet, profile);
        packagerArgsGenerator = new PackagerArgsGenerator(project, excelsiorJet);
    }

    /**
     * Generates Excelsior JET project file in {@code buildDir}
     *
     * @throws JetTaskFailureException if {@code buildDir} is not exists.
     */
    private String createJetCompilerProject() throws JetTaskFailureException {
        String prj = project.outputName() + ".prj";
        try (Writer writer = new BufferedWriter(new FileWriter(new File(buildDir, prj)))) {
            writer.write(compilerArgsGenerator.projectFileContent());
        } catch (IOException e) {
            throw new JetTaskFailureException(e.getMessage(), e);
        }
        return prj;
    }

    /**
     * Invokes the Excelsior JET AOT compiler.
     */
    private void compile(File buildDir) throws JetTaskFailureException, CmdLineToolException, IOException {
        String prj = createJetCompilerProject();
        if (excelsiorJet.compile(buildDir, "=p", prj, compilerArgsGenerator.jetVMPropOpt()) != 0) {
            throw new JetTaskFailureException(s("JetBuildTask.Build.Failure"));
        }
    }

    private boolean useXPackZipping() {
        return (!toProfile && (project.excelsiorJetPackaging() == PackagingType.ZIP) ||
                 toProfile && !project.isProfileLocally()) &&
                excelsiorJet.since11_3() &&
                (project.appType() != ApplicationType.WINDOWS_SERVICE) &&
                // JET-9267 workaround: cannot use xpack zipping when slimDown or diskFootprintReduction is enabled
                // The issue is fixed in JET 12.
                (excelsiorJet.since12_0() || (
                (project.runtimeConfiguration().slimDown == null) &&
                (project.runtimeConfiguration().diskFootprintReduction == null)));
    }

    private ArrayList<String> getXPackArgs(ArrayList<XPackOption> xpackOptions, File rspFile) throws JetTaskFailureException {
        if (excelsiorJet.since11_3()) {
            try {
                Utils.linesToFile(PackagerArgsGenerator.getArgFileContent(xpackOptions), rspFile);
            } catch (FileNotFoundException e) {
                throw new JetTaskFailureException("Cannot create file " + rspFile, e);
            }
            ArrayList<String> xpackArgs = PackagerArgsGenerator.optionsToArgs(xpackOptions, true);
            xpackArgs.add("-arg-file");
            xpackArgs.add(rspFile.getAbsolutePath());
            return xpackArgs;
        } else {
            return PackagerArgsGenerator.optionsToArgs(xpackOptions, false);
        }
    }

    private ArrayList<String> getCommonXPackArgs(String targetDir, File buildDir, String suffix) throws JetTaskFailureException {
        File rspFile = new File(buildDir, project.outputName() + suffix + ".xpack");
        ArrayList<XPackOption> xpackOptions = packagerArgsGenerator.getCommonXPackOptions(targetDir);
        return getXPackArgs(xpackOptions, rspFile);
    }

    /**
     * Packages the generated executable and required Excelsior JET runtime files
     * as a self-contained directory
     */
    private void createAppOrProfileDir(File buildDir, File appOrProfileDir) throws CmdLineToolException, JetTaskFailureException {
        ArrayList<String> xpackArgs = getCommonXPackArgs(appOrProfileDir.getAbsolutePath(), buildDir, ".SFD");
        if (useXPackZipping()) {
            //since 11.3 Excelsior JET supports zipping self-contained directories itself
            xpackArgs.add("-backend");
            xpackArgs.add("self-contained-directory"); //setting backend is needed for ARM 32 due to JET-8882 bug
            xpackArgs.add("-zip");
        }
        if (excelsiorJet.pack(buildDir, xpackArgs.toArray(new String[xpackArgs.size()])) != 0) {
            throw new JetTaskFailureException(s("JetBuildTask.Package.Failure"));
        }
        if (project.appType() == ApplicationType.WINDOWS_SERVICE) {
            try {
                createWinServiceInstallScripts(appOrProfileDir);
            } catch (IOException e) {
                throw new JetTaskFailureException(s("JetBuildTask.WinServiceScriptsCreation.Failure", e.toString()), e);
            }
        }
    }

    private void createWinServiceInstallScripts(File appDir) throws IOException {
        //copy isrv to app dir
        Utils.copyFile(new File(excelsiorJet.getJetHome() + File.separator + "bin", "isrv.exe").toPath(),
                new File(appDir, "isrv.exe").toPath());

        WindowsServiceScriptsGenerator scriptsGenerator = new WindowsServiceScriptsGenerator(project, excelsiorJet);
        String rspFile = project.outputName() + ".rsp";
        Utils.linesToFile(scriptsGenerator.isrvArgs(), new File(appDir, rspFile));
        Utils.linesToFile(scriptsGenerator.installBatFileContent(rspFile), new File(appDir, "install.bat"));
        Utils.linesToFile(scriptsGenerator.uninstallBatFileContent(), new File(appDir, "uninstall.bat"));
    }

    private ArrayList<String> getExcelsiorInstallerXPackArgs(File target, File buildDir) throws JetTaskFailureException {
        File rspFile = new File(buildDir, project.outputName() + ".EI.xpack");
        ArrayList<XPackOption> xpackOptions = packagerArgsGenerator.getExcelsiorInstallerXPackOptions(target);
        return getXPackArgs(xpackOptions, rspFile);
    }

    /**
     * Packages the generated executable and required Excelsior JET runtime files
     * as a excelsior installer file.
     */
    private void packWithEI(File buildDir) throws CmdLineToolException, JetTaskFailureException, IOException {
        File target = new File(project.jetOutputDir(), excelsiorJet.getTargetOS().mangleExeName(project.artifactName()));
        ArrayList<String> xpackArgs = getExcelsiorInstallerXPackArgs(target, buildDir);
        if (excelsiorJet.pack(buildDir, xpackArgs.toArray(new String[xpackArgs.size()])) != 0) {
            throw new JetTaskFailureException(s("JetBuildTask.Package.Failure"));
        }
        logger.info(s("JetBuildTask.Build.Success"));
        logger.info(s("JetBuildTask.GetEI.Info", target.getAbsolutePath()));
    }

    private void createOSXAppBundle(File buildDir) throws JetTaskFailureException, CmdLineToolException, IOException {
        File appBundle = new File(project.jetOutputDir(), project.osxBundleConfiguration().fileName + ".app");
        Utils.mkdir(appBundle);
        try {
            Utils.cleanDirectory(appBundle);
        } catch (IOException e) {
            throw new JetTaskFailureException(e.getMessage(), e);
        }
        File contents = new File(appBundle, "Contents");
        Utils.mkdir(contents);
        File contentsMacOs = new File(contents, "MacOS");
        Utils.mkdir(contentsMacOs);
        File contentsResources = new File(contents, "Resources");
        Utils.mkdir(contentsResources);

        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(new File(contents, "Info.plist")), "UTF-8"))) {
            out.print(
                    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                            "<!DOCTYPE plist PUBLIC \"-//Apple Computer//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
                            "<plist version=\"1.0\">\n" +
                            "<dict>\n" +
                            "  <key>CFBundlePackageType</key>\n" +
                            "  <string>APPL</string>\n" +
                            "  <key>CFBundleExecutable</key>\n" +
                            "  <string>" + project.outputName() + "</string>\n" +
                            "  <key>CFBundleName</key>\n" +
                            "  <string>" + project.osxBundleConfiguration().bundleName + "</string>\n" +
                            "  <key>CFBundleIdentifier</key>\n" +
                            "  <string>" + project.osxBundleConfiguration().identifier + "</string>\n" +
                            "  <key>CFBundleVersionString</key>\n" +
                            "  <string>" + project.osxBundleConfiguration().version + "</string>\n" +
                            "  <key>CFBundleShortVersionString</key>\n" +
                            "  <string>" + project.osxBundleConfiguration().shortVersion + "</string>\n" +
                            (project.osxBundleConfiguration().icon != null ?
                                    "  <key>CFBundleIconFile</key>\n" +
                                            "  <string>" + project.osxBundleConfiguration().icon.getName() + "</string>\n" : "") +
                            (project.osxBundleConfiguration().highResolutionCapable ?
                                    "  <key>NSHighResolutionCapable</key>\n" +
                                            "  <true/>" : "") +
                            "</dict>\n" +
                            "</plist>\n");
        }

        ArrayList<String> xpackArgs = getCommonXPackArgs(contentsMacOs.getAbsolutePath(), buildDir, ".OSXBundle");
        if (excelsiorJet.pack(buildDir, xpackArgs.toArray(new String[xpackArgs.size()])) != 0) {
            throw new JetTaskFailureException(s("JetBuildTask.Package.Failure"));
        }

        if (project.osxBundleConfiguration().icon != null) {
            Files.copy(project.osxBundleConfiguration().icon.toPath(),
                    new File(contentsResources, project.osxBundleConfiguration().icon.getName()).toPath());
        }

        File appPkg = null;
        if (project.osxBundleConfiguration().developerId != null) {
            logger.info(s("JetBuildTask.SigningOSXBundle.Info"));
            if (new CmdLineTool("codesign", "--verbose", "--force", "--deep", "--sign",
                    project.osxBundleConfiguration().developerId, appBundle.getAbsolutePath()).withLog(logger).execute() != 0) {
                throw new JetTaskFailureException(s("JetBuildTask.OSX.CodeSign.Failure"));
            }
            logger.info(s("JetBuildTask.CreatingOSXInstaller.Info"));
            if (project.osxBundleConfiguration().publisherId != null) {
                appPkg = new File(project.jetOutputDir(), project.artifactName() + ".pkg");
                if (new CmdLineTool("productbuild", "--sign", project.osxBundleConfiguration().publisherId,
                        "--component", appBundle.getAbsolutePath(), project.osxBundleConfiguration().installPath,
                        appPkg.getAbsolutePath())
                        .withLog(logger).execute() != 0) {
                    throw new JetTaskFailureException(s("JetBuildTask.OSX.Packaging.Failure"));
                }
            } else {
                logger.warn(s("JetBuildTask.NoPublisherId.Warning"));
            }
        } else {
            logger.warn(s("JetBuildTask.NoDeveloperId.Warning"));
        }
        logger.info(s("JetBuildTask.Build.Success"));
        if (appPkg != null) {
            logger.info(s("JetBuildTask.GetOSXPackage.Info", appPkg.getAbsolutePath()));
        } else {
            logger.info(s("JetBuildTask.GetOSXBundle.Info", appBundle.getAbsolutePath()));
        }

    }

    private File zipBuild(File packageDir) throws IOException {
        File targetZip = toProfile ? new File(project.jetAppToProfileDir().getAbsolutePath() + ".zip"):
                new File(project.jetOutputDir(), project.artifactName() + ".zip");
        if (useXPackZipping()) {
            if (!toProfile) {
                if (targetZip.exists()) {
                    if (!targetZip.delete() && targetZip.exists()) {
                        throw new IOException(s("JetApi.UnableToDelete.Error", packageDir.getAbsolutePath() + ".zip", targetZip));
                    }
                }
                if (!new File(packageDir.getAbsolutePath() + ".zip").renameTo(targetZip) && !targetZip.exists()) {
                    throw new IOException(s("JetBuildTask.UnableToRename.Error", packageDir.getAbsolutePath() + ".zip", targetZip));
                }
            }
        } else {
            logger.info(s("JetBuildTask.ZipApp.Info"));
            Utils.compressToZipFile(packageDir, targetZip);
        }
        return targetZip;
    }

    private void packageBuild(File buildDir, File packageDir) throws IOException, JetTaskFailureException, CmdLineToolException {
        switch (project.excelsiorJetPackaging()) {
            case ZIP:
                File targetZip = zipBuild(packageDir);
                logger.info(s("JetBuildTask.Build.Success"));
                logger.info(s("JetBuildTask.GetZip.Info", targetZip.getAbsolutePath()));
                break;
            case TAR_GZ:
                logger.info(s("JetBuildTask.ArchiveApp.Info"));
                File targetArchive = new File(project.jetOutputDir(), project.artifactName() + ".tar.gz");
                Utils.compressToTarGzFile(packageDir, targetArchive);
                logger.info(s("JetBuildTask.Build.Success"));
                logger.info(s("JetBuildTask.GetArchive.Info", targetArchive.getAbsolutePath()));
                break;
            case EXCELSIOR_INSTALLER:
                packWithEI(buildDir);
                break;
            case OSX_APP_BUNDLE:
                createOSXAppBundle(buildDir);
                break;
            default:
                logger.info(s("JetBuildTask.Build.Success"));
                logger.info(s("JetBuildTask.GetDir.Info", packageDir.getAbsolutePath()));
        }

        if (project.runtimeConfiguration().slimDown != null) {
            logger.info(s("JetBuildTask.SlimDown.Info", new File(project.jetOutputDir(), project.runtimeConfiguration().slimDown.detachedPackage),
                    project.runtimeConfiguration().slimDown.detachedBaseURL));
        }
    }

    private void collectProfile(File profileDir) throws JetTaskFailureException, IOException, CmdLineToolException {
        new RunTask(excelsiorJet, project, true).run(profileDir);
    }

    private long computeModifyTimeDaysBetween(File file1, File file2) {
        return TimeUnit.DAYS.convert(file2.lastModified() - file1.lastModified(), TimeUnit.MILLISECONDS);
    }

    private void checkProfileUpToDate(File profile, String warnKey) {
        if (profile.exists()) {
            long daysBefore = computeModifyTimeDaysBetween(profile, project.mainArtifact());
            if (daysBefore >= project.execProfiles().daysToWarnAboutOutdatedProfiles) {
                logger.warn(Txt.s(warnKey, profile.getAbsolutePath(), daysBefore));
            }
        }

    }

    void checkProfilesUpToDate() {
        ExecProfilesConfig execProfiles = project.execProfiles();
        if (execProfiles.daysToWarnAboutOutdatedProfiles > 0 ) {
            checkProfileUpToDate(execProfiles.getStartup(), "JetApi.TestRun.RecollectProfile.Warning");
            checkProfileUpToDate(execProfiles.getUsg(), "JetApi.TestRun.RecollectProfile.Warning");
            checkProfileUpToDate(execProfiles.getJProfile(), "JetApi.PGO.RecollectProfile.Warning");
        }
    }

    /**
     * Builds project, that was specified in constructor
     *
     * @throws JetTaskFailureException if any task specific error conditions occurs
     * @throws IOException if any I/O error occurs
     * @throws CmdLineToolException if any error occurs while cmd line tool calls
     */
    public void execute() throws JetTaskFailureException, IOException, CmdLineToolException {
        if (toProfile && !excelsiorJet.isPGOSupported()) {
            throw new JetTaskFailureException(Txt.s("JetApi.PGONotSupported.Failure"));
        }

        project.validate(excelsiorJet, true);
        buildDir = project.createBuildDir();

        File appOrProfileDir = toProfile ? project.jetAppToProfileDir(): project.jetAppDir();
        //cleanup appDir
        try {
            Utils.cleanDirectory(appOrProfileDir);
        } catch (IOException e) {
            throw new JetTaskFailureException(e.getMessage(), e);
        }

        switch (project.appType()) {
            case PLAIN:
            case DYNAMIC_LIBRARY:
            case WINDOWS_SERVICE:
                project.copyClasspathEntries();
                compile(buildDir);
                break;
            case TOMCAT:
                project.copyTomcatAndWar();
                compile(buildDir);
                break;
            case SPRING_BOOT:
                project.copySpringBootArtifact();
                compile(buildDir);
                break;
            default:
                throw new AssertionError("Unknown application type");
        }

        createAppOrProfileDir(buildDir, appOrProfileDir);

        if (toProfile) {
            Utils.mkdir(project.execProfiles().outputDir);
            if (project.isProfileLocally()) {
                switch (project.appType()) {
                    case WINDOWS_SERVICE:
                        logger.info(Txt.s("JetApi.Profile.WinService", project.execProfiles().profilingImageDir.getAbsolutePath()));
                        break;
                    case DYNAMIC_LIBRARY:
                        logger.info(Txt.s("JetApi.Profile.DynamicLibrary", project.execProfiles().profilingImageDir.getAbsolutePath()));
                        break;
                    case PLAIN:
                    case TOMCAT:
                    case SPRING_BOOT:
                        collectProfile(appOrProfileDir);
                        if (project.execProfiles().getJProfile().exists()) {
                            logger.info(Txt.s("JetApi.Profile.ProfileCollected"));
                        } else {
                            logger.error(Txt.s("JetApi.Profile.ProfileNotCollected"));
                        }
                        break;
                    default:
                        throw new AssertionError("Unknown application type");
                }
            } else {
                File zipFile = zipBuild(buildDir);
                logger.info(Txt.s("JetApi.Profile.NotLocally",
                        project.execProfiles().profilingImageDir.getAbsolutePath(), zipFile.getAbsolutePath(),
                        project.execProfiles().getJProfile().getName(), project.execProfiles().outputDir.getAbsolutePath()));
            }
        } else {
            packageBuild(buildDir, appOrProfileDir);
            checkProfilesUpToDate();
        }
    }
}
