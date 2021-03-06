/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.wtp.filtering;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.MavenExecutionPlan;
import org.apache.maven.plugin.MojoExecution;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Status;
import org.maven.ide.eclipse.MavenPlugin;
import org.maven.ide.eclipse.core.MavenLogger;
import org.maven.ide.eclipse.embedder.IMaven;
import org.maven.ide.eclipse.internal.project.GenericBuildParticipant;
import org.maven.ide.eclipse.project.IMavenProjectFacade;
import org.maven.ide.eclipse.project.MavenProjectManager;
import org.maven.ide.eclipse.project.ResolverConfiguration;
import org.maven.ide.eclipse.wtp.internal.MavenWtpPlugin;

/**
 * ResourceFilteringBuildParticipant
 *
 * @author Fred Bricon
 */
public class ResourceFilteringBuildParticipant extends GenericBuildParticipant {

  public Set<IProject> build(int kind, IProgressMonitor monitor) throws Exception {
    IMavenProjectFacade facade = getMavenProjectFacade();
    ResourceFilteringConfiguration configuration = ResourceFilteringConfigurationFactory.getConfiguration(facade);
    if (configuration == null || configuration.getResources() == null) {
      //Nothing to filter
      return null;
    }

    IProject project = facade.getProject();
    List<Xpp3Dom> resources = configuration.getResources();
    //FIXME assuming path relative to current project
    IPath targetFolder = configuration.getTargetFolder();
    
    if (hasResourcesChanged(facade, getDelta(project), resources)) {
      MavenLogger.log("Executing resource filtering for "+project.getName());
      executeCopyResources(facade, targetFolder, resources, monitor);  
      //FIXME deal with absolute paths
      IFolder destFolder = project.getFolder(targetFolder);
      if (destFolder.exists()){
        destFolder.refreshLocal(IResource.DEPTH_INFINITE, monitor);
      }
    }    
    return null;
  
  }
  
  /* (non-Javadoc)
   * @see org.maven.ide.eclipse.project.configurator.AbstractBuildParticipant#clean(org.eclipse.core.runtime.IProgressMonitor)
   */
  public void clean(IProgressMonitor monitor) throws CoreException {
    IMavenProjectFacade facade = getMavenProjectFacade();
    ResourceFilteringConfiguration configuration = ResourceFilteringConfigurationFactory.getConfiguration(facade);
    if (configuration == null) {
      //Nothing to do
      return;
    }

    IProject project = facade.getProject();
    IPath targetFolderPath = configuration.getTargetFolder();
    IFolder targetFolder = project.getFolder(targetFolderPath);
    if (targetFolder.exists()) {
      IContainer parent = targetFolder.getParent(); 
      MavenLogger.log("Cleaning filtered folder for "+project.getName());
      targetFolder.delete(true, new NullProgressMonitor());
      if (parent != null) {
        parent.refreshLocal(IResource.DEPTH_INFINITE, monitor); 
      }
    }
    super.clean(monitor);
  }
  
  /**
   * @param mavenProject
   * @param iResourceDelta 
   * @param resources
   * @return
   */
  private boolean hasResourcesChanged(IMavenProjectFacade facade, IResourceDelta delta, List<Xpp3Dom> resources) {
    if (resources == null || resources.isEmpty()){
      return false;
    }
      
    Set<IPath> resourcePaths = getResourcePaths(facade, resources);
  
    if(delta == null) {
      return !resourcePaths.isEmpty();
    }
  
    for(IPath resourcePath : resourcePaths) {
      IResourceDelta member = delta.findMember(resourcePath);
      //XXX deal with member kind/flags
      if(member != null) {
          return true; 
          //we need to deal with superceded resources on the maven level
      }
    }
  
    return false;
  }

  
  private Set<IPath> getResourcePaths(IMavenProjectFacade facade, List<Xpp3Dom> resources) {
    Set<IPath> resourcePaths = new LinkedHashSet<IPath>();
    for(Xpp3Dom resource : resources) {
      IPath folder= null;
      Xpp3Dom xpp3Directory = resource.getChild("directory");
      if (xpp3Directory != null)
      {
        String dir = xpp3Directory.getValue();
        if (StringUtils.isNotEmpty(dir)){
          IPath dirPath = new Path(dir);
          folder = dirPath.makeRelativeTo(facade.getProject().getLocation());          
        }
      }
      if(folder != null && !folder.isEmpty()) {
        resourcePaths.add(folder);
      }
    }
    if (!resourcePaths.isEmpty()) {
      //Filtering is triggered in case a change in pom.xml has modified the resource list 
      resourcePaths.add(facade.getPom().getProjectRelativePath());
    }
    return resourcePaths;
  }
  
  /**
   * @param facade
   * @param project 
   * @param targetFolder
   * @param resources
   * @param monitor 
   * @throws CoreException 
   */
  private void executeCopyResources(IMavenProjectFacade facade, IPath targetFolder, List<Xpp3Dom> resources, IProgressMonitor monitor) throws CoreException {

    MavenExecutionPlan executionPlan = facade.getExecutionPlan(monitor);
    MojoExecution copyFilteredResourcesMojo = getExecution(executionPlan, "maven-resources-plugin");

    if (copyFilteredResourcesMojo == null) return;

    Xpp3Dom  configuration = copyFilteredResourcesMojo.getConfiguration();
    
    Xpp3Dom  resourcesNode = new Xpp3Dom("resources");
    for (Xpp3Dom resource : resources)
    {
      resourcesNode.addChild(resource);
    }
    configuration.addChild(resourcesNode);

    Boolean overwrite = Boolean.TRUE;
    Xpp3Dom  overwriteNode = new Xpp3Dom("overwrite");
    overwriteNode.setValue(overwrite.toString());
    configuration.addChild(overwriteNode);
      
        
    Xpp3Dom  outPutDirNode = new Xpp3Dom("outputDirectory");
    outPutDirNode.setValue(targetFolder.toPortableString());
    configuration.addChild(outPutDirNode);
    
    MavenProjectManager projectManager = MavenPlugin.getDefault().getMavenProjectManager();

    //Create a maven request + session
    IMaven maven = MavenPlugin.getDefault().getMaven();
    ResolverConfiguration resolverConfig = facade.getResolverConfiguration();
    
    MavenExecutionRequest request = projectManager.createExecutionRequest(facade.getPom(), resolverConfig, monitor);
    request.setRecursive(false);
    request.setOffline(true);
    MavenSession session = maven.createSession(request, facade.getMavenProject());

    //Execute our hacked mojo 
    copyFilteredResourcesMojo.getMojoDescriptor().setGoal("copy-resources");
    maven.execute(session, copyFilteredResourcesMojo, monitor);

    logErrors(session.getResult(), facade.getProject().getName());  
  }

  //TODO change visibility of GenericBuildPArticipant.logErrors to protected
  void logErrors(MavenExecutionResult result, String projectNname) {
    if(result.hasExceptions()) {
      String msg = "Build errors for " + projectNname;
      List<Throwable> exceptions = result.getExceptions();
      for(Throwable ex : exceptions) {
        MavenPlugin.getDefault().getConsole().logError(msg + "; " + ex.toString());
        MavenLogger.log(msg, ex);
      }

      // XXX add error markers
    }

  }


  
  private MojoExecution getExecution(MavenExecutionPlan executionPlan, String artifactId) throws CoreException {
    for(MojoExecution execution : getMojoExecutions(executionPlan)) {
      if(artifactId.equals(execution.getArtifactId()) ) {
        return execution;
      }
    }
    return null;
  }

  private Collection<MojoExecution> getMojoExecutions(MavenExecutionPlan executionPlan) throws CoreException {
    Collection<MojoExecution> mojoExecutions;
    try {
      mojoExecutions = executionPlan.getMojoExecutions();
    } catch (NoSuchMethodError nsme) {
      //Support older versions of m2eclipse-core (pre Maven 3 era)
      try {
        Method getExecutionsMethod = MavenExecutionPlan.class.getMethod("getExecutions");
        mojoExecutions = (Collection<MojoExecution>) getExecutionsMethod.invoke(executionPlan);
      } catch(Exception e) {
        IStatus status = new Status(IStatus.ERROR, MavenWtpPlugin.ID, IStatus.ERROR, e.getMessage(), e);
        throw new CoreException(status);
      }
    }
    return mojoExecutions;
  }

  public static ResourceFilteringBuildParticipant getParticipant(MojoExecution execution) {
    if ("maven-war-plugin".equals(execution.getArtifactId()) && "war".equals(execution.getGoal()))
    {
      return new ResourceFilteringBuildParticipant(); 
    }
    return null;
  }
}
