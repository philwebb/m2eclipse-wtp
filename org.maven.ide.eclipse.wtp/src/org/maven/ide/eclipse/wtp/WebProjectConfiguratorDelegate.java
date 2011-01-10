/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.wtp;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jst.j2ee.classpathdep.IClasspathDependencyConstants;
import org.eclipse.jst.j2ee.commonarchivecore.internal.helpers.ArchiveManifest;
import org.eclipse.jst.j2ee.commonarchivecore.internal.helpers.ArchiveManifestImpl;
import org.eclipse.jst.j2ee.internal.J2EEConstants;
import org.eclipse.jst.j2ee.internal.project.J2EEProjectUtilities;
import org.eclipse.jst.j2ee.project.facet.IJ2EEModuleFacetInstallDataModelProperties;
import org.eclipse.jst.j2ee.web.project.facet.IWebFacetInstallDataModelProperties;
import org.eclipse.jst.j2ee.web.project.facet.WebFacetInstallDataModelProvider;
import org.eclipse.jst.j2ee.web.project.facet.WebFacetUtils;
import org.eclipse.wst.common.componentcore.ComponentCore;
import org.eclipse.wst.common.componentcore.resources.IVirtualComponent;
import org.eclipse.wst.common.componentcore.resources.IVirtualFolder;
import org.eclipse.wst.common.componentcore.resources.IVirtualReference;
import org.eclipse.wst.common.componentcore.resources.IVirtualResource;
import org.eclipse.wst.common.frameworks.datamodel.DataModelFactory;
import org.eclipse.wst.common.frameworks.datamodel.IDataModel;
import org.eclipse.wst.common.project.facet.core.IFacetedProject;
import org.eclipse.wst.common.project.facet.core.IFacetedProject.Action;
import org.eclipse.wst.common.project.facet.core.IProjectFacetVersion;
import org.eclipse.wst.common.project.facet.core.ProjectFacetsManager;
import org.maven.ide.eclipse.MavenPlugin;
import org.maven.ide.eclipse.core.MavenLogger;
import org.maven.ide.eclipse.embedder.IMaven;
import org.maven.ide.eclipse.jdt.IClasspathDescriptor;
import org.maven.ide.eclipse.jdt.IClasspathEntryDescriptor;
import org.maven.ide.eclipse.project.IMavenProjectFacade;
import org.maven.ide.eclipse.wtp.filtering.WebResourceFilteringConfiguration;
import org.maven.ide.eclipse.wtp.internal.AntPathMatcher;
import org.maven.ide.eclipse.wtp.internal.ExtensionReader;


/**
 * WebProjectConfiguratorDelegate
 *
 * @author Igor Fedorenko
 * @author Fred Bricon
 */
@SuppressWarnings("restriction")
class WebProjectConfiguratorDelegate extends AbstractProjectConfiguratorDelegate {

  /**
   * See http://wiki.eclipse.org/ClasspathEntriesPublishExportSupport
   */
  static final IClasspathAttribute DEPENDENCY_ATTRIBUTE = JavaCore.newClasspathAttribute(
      IClasspathDependencyConstants.CLASSPATH_COMPONENT_DEPENDENCY, "/WEB-INF/lib");

  static final IClasspathAttribute CONSUMED_ATTRIBUTE = JavaCore.newClasspathAttribute(
      "org.eclipse.jst.component.consumed", "");

  static final IClasspathAttribute ROOT_DEPENDENCY_ATTRIBUTE = JavaCore.newClasspathAttribute(
      IClasspathDependencyConstants.CLASSPATH_COMPONENT_DEPENDENCY,
      IClasspathDependencyConstants.RUNTIME_MAPPING_INTO_COMPONENT);

  static final IClasspathAttribute WEBINFCLASSES_DEPENDENCY_ATTRIBUTE = JavaCore.newClasspathAttribute(
      IClasspathDependencyConstants.CLASSPATH_COMPONENT_DEPENDENCY,
      J2EEConstants.WEB_INF_CLASSES);

  /**
  * Name of maven property that overrides WTP context root.
  */
  private static final String M2ECLIPSE_WTP_CONTEXT_ROOT = "m2eclipse.wtp.contextRoot";

  /**
   * Name of maven property that allows additional deployments
   */
  private static final String DEPLOY_PROPERTY = "m2eclipse.wtp.deploy";


  public Set<Artifact> resolveAdditionalArtifacts(IMavenProjectFacade facade, IProgressMonitor monitor) throws CoreException {
    Set<Artifact> rtn = new LinkedHashSet<Artifact>();
    IMaven maven = MavenPlugin.getDefault().getMaven();
    MavenProject mavenProject = facade.getMavenProject(monitor);
    for(Artifact artifact : mavenProject.getArtifacts()) {
      if("war-overlay".equals(artifact.getType()))
      {
        Artifact contentArtifact = maven.resolve(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), artifact.getType(), "webcontent", null, monitor);
        if(contentArtifact != null) {
          rtn.add(contentArtifact);
        }
      }
    }
    return rtn;
  }

  public ArtifactFilter getClasspathFilter(final IClasspathDescriptor classpath) {
    return new WarOverlayHandledByProjectFilter(classpath);
  }

  protected void configure(IProject project, MavenProject mavenProject, IProgressMonitor monitor)
      throws CoreException {
    IFacetedProject facetedProject = ProjectFacetsManager.create(project, true, monitor);

    // make sure to update the main deployment folder
    WarPluginConfiguration config = new WarPluginConfiguration(mavenProject, project);
    String warSourceDirectory = config.getWarSourceDirectory();
    IFile defaultWebXml = project.getFolder(warSourceDirectory).getFile("WEB-INF/web.xml");
    IFolder libDir = project.getFolder(warSourceDirectory).getFolder("WEB-INF/lib");

    boolean alreadyHasWebXml = defaultWebXml.exists();
    boolean alreadyHasLibDir = libDir.exists();

    Set<Action> actions = new LinkedHashSet<Action>();

    installJavaFacet(actions, project, facetedProject);

    IVirtualComponent component = ComponentCore.createComponent(project);
    if(component != null && warSourceDirectory != null) {
      IPath warPath = new Path(warSourceDirectory);
      //remove the old links (if there is one) before adding the new one.
      component.getRootFolder().removeLink(warPath,IVirtualResource.NONE, monitor);
      component.getRootFolder().createLink(warPath, IVirtualResource.NONE, monitor);
    }

    //MNGECLIPSE-2279 get the context root from the final name of the project, or artifactId by default.
    String contextRoot = getContextRoot(mavenProject);

    IProjectFacetVersion webFv = config.getWebFacetVersion(project);
    if(!facetedProject.hasProjectFacet(WebFacetUtils.WEB_FACET)) {
      installWebFacet(mavenProject, warSourceDirectory, contextRoot, actions, webFv);
    } else {
      IProjectFacetVersion projectFacetVersion = facetedProject.getProjectFacetVersion(WebFacetUtils.WEB_FACET);
      if(webFv.getVersionString() != null && !webFv.getVersionString().equals(projectFacetVersion.getVersionString())){
        try {
          Action uninstallAction = new IFacetedProject.Action(IFacetedProject.Action.Type.UNINSTALL,
                                       facetedProject.getInstalledVersion(WebFacetUtils.WEB_FACET),
                                       null);
          facetedProject.modify(Collections.singleton(uninstallAction), monitor);
        } catch(Exception ex) {
          MavenLogger.log("Error removing WEB facet", ex);
        }
        installWebFacet(mavenProject, warSourceDirectory, contextRoot, actions, webFv);
      }
    }

    if(!actions.isEmpty()) {
      facetedProject.modify(actions, monitor);
    }

    // MNGECLIPSE-632 remove test sources/resources from WEB-INF/classes
    removeTestFolderLinks(project, mavenProject, monitor, "/WEB-INF/classes");

    addContainerAttribute(project, DEPENDENCY_ATTRIBUTE, monitor);

    //MNGECLIPSE-2279 change the context root if needed
    if (!contextRoot.equals(J2EEProjectUtilities.getServerContextRoot(project))) {
      J2EEProjectUtilities.setServerContextRoot(project, contextRoot);
    }

    //MNGECLIPSE-2357 support custom location of web.xml
    String customWebXml = config.getCustomWebXml(project);
    //If we have a custom web.xml but WTP created one against our will, we delete it
    if (customWebXml != null && !alreadyHasWebXml && defaultWebXml.exists()) {
      defaultWebXml.delete(true, monitor);
    }
    //Maven /m2eclipse doesn't need a new lib dir.
    if (!alreadyHasLibDir && libDir.exists()) {
      libDir.delete(true, monitor);
    }
    linkFile(project, customWebXml, "WEB-INF/web.xml", monitor);


    IPath filteredFolder = WebResourceFilteringConfiguration.getTargetFolder(mavenProject, project);
    if (component != null) {
      ProjectUtils.hideM2eclipseWtpFolder(mavenProject, project);
      component.getRootFolder().removeLink(filteredFolder,IVirtualResource.NONE, monitor);
      if (config.getWebResources() != null && config.getWebResources().length > 0) {
        component.getRootFolder().createLink(filteredFolder, IVirtualResource.NONE, monitor);
      }
      configureAdditionalFolders(mavenProject, component, monitor);
    }
  }

  /**
   * @param component
   * @throws CoreException
   */
  private void configureAdditionalFolders(MavenProject mavenProject, IVirtualComponent component, IProgressMonitor monitor) throws CoreException {
    String deploymentsList = (String) mavenProject.getProperties().get(DEPLOY_PROPERTY);
    if(!StringUtils.isBlank(deploymentsList)) {
      for(String deployment : deploymentsList.split(",")) {
        Path path = new Path(deployment);
        IVirtualFolder folder = component.getRootFolder().getFolder(path);
        folder.createLink(path, IVirtualResource.NONE, monitor);
      }
    }
  }

  /**
   * Install a Web Facet version
   * @param mavenProject
   * @param warSourceDirectory
   * @param actions
   * @param webFv
   */
  private void installWebFacet(MavenProject mavenProject, String warSourceDirectory, String contextRoot, Set<Action> actions,
      IProjectFacetVersion webFv) {
    IDataModel webModelCfg = DataModelFactory.createDataModel(new WebFacetInstallDataModelProvider());
    webModelCfg.setProperty(IJ2EEModuleFacetInstallDataModelProperties.CONFIG_FOLDER, warSourceDirectory);
    webModelCfg.setProperty(IWebFacetInstallDataModelProperties.CONTEXT_ROOT, contextRoot);
    webModelCfg.setProperty(IJ2EEModuleFacetInstallDataModelProperties.GENERATE_DD, false);
    actions.add(new IFacetedProject.Action(IFacetedProject.Action.Type.INSTALL, webFv, webModelCfg));
  }

  public void setModuleDependencies(IProject project, MavenProject mavenProject, IProgressMonitor monitor)
      throws CoreException {
    IVirtualComponent component = ComponentCore.createComponent(project);
    //if the attempt to create dependencies happens before the project is actually created, abort.
    //this will be created again when the project exists.
    if(component == null){
      return;
    }

    WarPluginConfiguration config = new WarPluginConfiguration(mavenProject, project);
    WarPackagingOptions opts = new WarPackagingOptions(config);

    List<AbstractDependencyConfigurator> depConfigurators = ExtensionReader.readDependencyConfiguratorExtensions(projectManager,
        MavenPlugin.getDefault().getMavenRuntimeManager(), mavenMarkerManager,
        MavenPlugin.getDefault().getConsole());

    Set<IVirtualReference> references = new LinkedHashSet<IVirtualReference>();
    List<IMavenProjectFacade> exportedDependencies = getWorkspaceDependencies(project, mavenProject);
    for(IMavenProjectFacade dependency : exportedDependencies) {
      String depPackaging = dependency.getPackaging();
      if ("pom".equals(depPackaging)) continue;//MNGECLIPSE-744 pom dependencies shouldn't be deployed

      preConfigureDependencyProject(dependency, monitor);
      MavenProject depMavenProject =  dependency.getMavenProject(monitor);

      IVirtualComponent depComponent = ComponentCore.createComponent(dependency.getProject());

      String artifactKey = ArtifactUtils.versionlessKey(depMavenProject.getArtifact());
      Artifact artifact = mavenProject.getArtifactMap().get(artifactKey);
      //in a skinny war the dependency modules are referenced by manifest classpath
      //see also <code>configureClasspath</code> the dependeny project is handled in the skinny case
      if(opts.isSkinnyWar() && opts.isReferenceFromEar(depComponent, artifact.getArtifactHandler().getExtension())) {
        continue;
      }

      //an artifact in mavenProject.getArtifacts() doesn't have the "optional" value as depMavenProject.getArtifact();
      if (!artifact.isOptional()) {
        IVirtualReference reference = ComponentCore.createReference(component, depComponent);
        String artifactType = depMavenProject.getArtifact().getType();
        if("war".equals(artifactType) || "war-overlay".equals(artifactType)) {
          reference.setRuntimePath(new Path("/"));
          reference.setDependencyType(IVirtualReference.DEPENDENCY_TYPE_CONSUMES);
        } else {
          reference.setRuntimePath(new Path("/WEB-INF/lib"));
        }
        references.add(reference);
      }
    }


    IVirtualReference[] newRefs = references.toArray(new IVirtualReference[references.size()]);
    if (hasChanged(component.getReferences(), newRefs)){
      //Only write in the .component file if necessary
      component.setReferences(newRefs);
    }

    //TODO why a 2nd loop???
    for(IMavenProjectFacade dependency : exportedDependencies) {
      MavenProject depMavenProject =  dependency.getMavenProject(monitor);
      Iterator<AbstractDependencyConfigurator> configurators = depConfigurators.iterator();
      while (configurators.hasNext()) {
        try {
          configurators.next().configureDependency(mavenProject, project, depMavenProject, dependency.getProject(), monitor);
        } catch(MarkedException ex) {
          //XXX handle this
        }
      }
    }
  }

  /**
   * Get the context root from a maven web project
   * @param mavenProject
   * @return the final name of the project if it exists, or the project's artifactId.
   */
  protected String getContextRoot(MavenProject mavenProject) {
    String contextRoot;

	//MECLIPSEWTP-43 : Override with maven property
   String property = mavenProject.getProperties().getProperty(M2ECLIPSE_WTP_CONTEXT_ROOT);
   if (StringUtils.isEmpty(property)) {
		String finalName = mavenProject.getBuild().getFinalName();
		if (StringUtils.isBlank(finalName)
		   || finalName.equals(mavenProject.getArtifactId() + "-" + mavenProject.getVersion())) {
		  contextRoot = mavenProject.getArtifactId();
		}  else {
		  contextRoot = finalName;
		}
	} else {
		contextRoot = property;
	}
    return contextRoot.trim().replace(" ", "_");
  }

  public void configureClasspath(IProject project, MavenProject mavenProject, IClasspathDescriptor classpath,
      IProgressMonitor monitor) throws CoreException {

    //Improve skinny war support by generating the manifest classpath
    //similar to mvn eclipse:eclipse
    //http://maven.apache.org/plugins/maven-war-plugin/examples/skinny-wars.html
    WarPluginConfiguration config = new WarPluginConfiguration(mavenProject, project);
    WarPackagingOptions opts = new WarPackagingOptions(config);

    StringBuilder manifestCp = new StringBuilder();

    /*
     * Need to take care of three separate cases
     *
     * 1. remove any project dependencies (they are represented as J2EE module dependencies)
     * 2. add non-dependency attribute for entries originated by artifacts with
     *    runtime, system, test scopes or optional dependencies (not sure about the last one)
     * 3. make sure all dependency JAR files have unique file names, i.e. artifactId/version collisions
     */

    Set<String> dups = new LinkedHashSet<String>();
    Set<String> names = new HashSet<String>();

    // first pass removes projects, adds non-dependency attribute and collects colliding filenames
    Iterator<IClasspathEntryDescriptor> iter = classpath.getEntryDescriptors().iterator();
    while (iter.hasNext()) {
      IClasspathEntryDescriptor descriptor = iter.next();
      IClasspathEntry entry = descriptor.getClasspathEntry();
      String scope = descriptor.getScope();
      String key = ArtifactUtils.versionlessKey(descriptor.getGroupId(),descriptor.getArtifactId());
      Artifact artifact = mavenProject.getArtifactMap().get(key);
      String extension = artifact.getArtifactHandler().getExtension();

      if(IClasspathEntry.CPE_PROJECT == entry.getEntryKind() && Artifact.SCOPE_COMPILE.equals(scope)) {
        //get deployed name for project dependencies
        //TODO can this be done somehow more elegantly?
        IProject p = (IProject) ResourcesPlugin.getWorkspace().getRoot().findMember(entry.getPath());

        IVirtualComponent component = ComponentCore.createComponent(p);

        boolean usedInEar = opts.isReferenceFromEar(component, extension);
        if(opts.isSkinnyWar() && usedInEar) {
          if(manifestCp.length() > 0) {
            manifestCp.append(" ");
          }
          //MNGECLIPSE-2393 prepend ManifestClasspath prefix
          if (config.getManifestClasspathPrefix() != null && !JEEPackaging.isJEEPackaging(artifact.getType())) {
              manifestCp.append(config.getManifestClasspathPrefix());
          }

          manifestCp.append(component.getDeployedName()).append(".").append(extension);
        }

        if (!descriptor.isOptionalDependency() || usedInEar) {
          // remove mandatory project dependency from classpath
          iter.remove();
          continue;
        }//else : optional dependency not used in ear -> need to trick ClasspathAttribute with NONDEPENDENCY_ATTRIBUTE
      }

      if(opts.isSkinnyWar() && opts.isReferenceFromEar(descriptor)) {

        if(manifestCp.length() > 0) {
          manifestCp.append(" ");
        }
        if(config.getManifestClasspathPrefix() != null && !JEEPackaging.isJEEPackaging(artifact.getType())) {
          manifestCp.append(config.getManifestClasspathPrefix());
        }
        manifestCp.append(entry.getPath().lastSegment());

        // ear references aren't kept in the Maven Dependencies
        iter.remove();
        continue;
      }

      // add non-dependency attribute
      // Check the scope & set WTP non-dependency as appropriate
      // Optional artifact shouldn't be deployed
      if(Artifact.SCOPE_PROVIDED.equals(scope) || Artifact.SCOPE_TEST.equals(scope)
          || Artifact.SCOPE_SYSTEM.equals(scope) || descriptor.isOptionalDependency()) {
        descriptor.addClasspathAttribute(NONDEPENDENCY_ATTRIBUTE);
      }

      //Check for war overlays
      if("war".equals(descriptor.getType())) {
        descriptor.addClasspathAttribute(CONSUMED_ATTRIBUTE);
        descriptor.addClasspathAttribute(ROOT_DEPENDENCY_ATTRIBUTE);
      }
      if("war-overlay".equals(descriptor.getType())) {
        descriptor.addClasspathAttribute(CONSUMED_ATTRIBUTE);
        if("webcontent".equals(descriptor.getClassifier())) {
          descriptor.addClasspathAttribute(ROOT_DEPENDENCY_ATTRIBUTE);
        } else {
          descriptor.addClasspathAttribute(WEBINFCLASSES_DEPENDENCY_ATTRIBUTE);
        }
      }

      // collect duplicate file names
      if (!names.add(entry.getPath().lastSegment())) {
        dups.add(entry.getPath().lastSegment());
      }
    }

    String targetDir = mavenProject.getBuild().getDirectory();

    // second pass disambiguates colliding entry file names
    iter = classpath.getEntryDescriptors().iterator();
    while (iter.hasNext()) {
      IClasspathEntryDescriptor descriptor = iter.next();
      IClasspathEntry entry = descriptor.getClasspathEntry();

      if (dups.contains(entry.getPath().lastSegment())) {
        File src = new File(entry.getPath().toOSString());
        String groupId = descriptor.getGroupId();
        File dst = new File(targetDir, groupId + "-" + entry.getPath().lastSegment());
        try {
          if (src.canRead()) {
            if (isDifferent(src, dst)) { // uses lastModified
              FileUtils.copyFile(src, dst);
              dst.setLastModified(src.lastModified());
            }
            descriptor.setClasspathEntry(JavaCore.newLibraryEntry(Path.fromOSString(dst.getCanonicalPath()), //
                entry.getSourceAttachmentPath(), //
                entry.getSourceAttachmentRootPath(), //
                entry.getAccessRules(), //
                entry.getExtraAttributes(), //
                entry.isExported()));
          }
        } catch(IOException ex) {
          MavenLogger.log("File copy failed", ex);
        }
      }

    }

    if(opts.isSkinnyWar()) {

      //writing the manifest only works when the project has been properly created
      //placing this check on the top of the method broke 2 other tests
      //thats why its placed here now.
      if(ComponentCore.createComponent(project) == null) {
        return;
      }

      //write manifest, using internal API - seems ok for 3.4/3.5, though
      ArchiveManifest mf = J2EEProjectUtilities.readManifest(project);
      if(mf == null) {
        mf = new ArchiveManifestImpl();
      }
      mf.addVersionIfNecessary();
      mf.setClassPath(manifestCp.toString());

      try {
        J2EEProjectUtilities.writeManifest(project, mf);
      } catch(Exception ex) {
        MavenLogger.log("Could not write web module manifest file", ex);
      }
    }
  }

  private static boolean isDifferent(File src, File dst) {
    if (!dst.exists()) {
      return true;
    }

    return src.length() != dst.length()
        || src.lastModified() != dst.lastModified();
  }

  private static class WarPackagingOptions {

    private boolean isAddManifestClasspath;

    //these are used in the skinny use case to decide wheter a dependencies gets
    //referenced from the ear, or if it is (exceptionally) placed in the WEB-INF/lib
    String[] packagingIncludes;

    String[] packagingExcludes;

    public WarPackagingOptions(WarPluginConfiguration config) {

      isAddManifestClasspath = config.isAddManifestClasspath();

      packagingExcludes = config.getPackagingExcludes();
      packagingIncludes = config.getPackagingIncludes();
    }

    public boolean isSkinnyWar() {
      return isAddManifestClasspath;
    }

    public boolean isReferenceFromEar(IClasspathEntryDescriptor descriptor) {

      IClasspathEntry entry = descriptor.getClasspathEntry();
      String scope = descriptor.getScope();

      //these dependencies aren't added to the manifest cp
      //retain optional dependencies here, they might be used just to express the
      //dependency to be used in the manifest
      if(Artifact.SCOPE_PROVIDED.equals(scope) || Artifact.SCOPE_TEST.equals(scope)
          || Artifact.SCOPE_SYSTEM.equals(scope)) {
        return false;
      }

      //calculate in regard to includes/excludes whether this jar is
      //to be packaged into  WEB-INF/lib
      String jarFileName = "WEB-INF/lib/" + entry.getPath().lastSegment();
      return isExcludedFromWebInfLib(jarFileName);
    }

    /**
     * @param depComponent
     * @return
     */
    public boolean isReferenceFromEar(IVirtualComponent depComponent, String extension) {

      if (depComponent==null) {
        return false;
      }

      //calculate in regard to includes/excludes wether this jar is
      //to be packaged into  WEB-INF/lib
      String jarFileName = "WEB-INF/lib/" + depComponent.getDeployedName() + "." + extension;
      return isExcludedFromWebInfLib(jarFileName);
    }

    private boolean isExcludedFromWebInfLib(String virtualLibPath) {

      AntPathMatcher matcher = new AntPathMatcher();

      for(String excl : packagingExcludes) {
        if(matcher.match(excl, virtualLibPath)) {

          //stop here already, since exclusions seem to have precedence over inclusions
          //it is not documented as such for the maven war-plugin, I concluded this from experimentation
          //should be verfied, though
          return true;
        }
      }

      //so the path is not excluded, check if it is included into the war packaging
      for(String incl : packagingIncludes) {
        if(matcher.match(incl, virtualLibPath)) {
          return false;
        }
      }

      //if we're here it means the path has not been specifically included either
      //that means either no inclusions are defined at all (<packagingIncludes> missing or empty)
      //or the jar is really not included
      if(packagingIncludes.length == 0) {
        //undefined inclusions mean maven war plugin default -> will be included in war
        return false;
      } else {
        //specifically not included
        return true;
      }
    }
  }
}
