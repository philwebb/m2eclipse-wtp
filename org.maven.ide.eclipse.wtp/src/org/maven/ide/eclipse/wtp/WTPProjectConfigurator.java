/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.wtp;

import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.wst.common.componentcore.ModuleCoreNature;
import org.maven.ide.eclipse.core.MavenLogger;
import org.maven.ide.eclipse.jdt.IClasspathDescriptor;
import org.maven.ide.eclipse.jdt.IExtendedJavaProjectConfigurator;
import org.maven.ide.eclipse.project.IMavenProjectFacade;
import org.maven.ide.eclipse.project.MavenProjectChangedEvent;
import org.maven.ide.eclipse.project.configurator.AbstractBuildParticipant;
import org.maven.ide.eclipse.project.configurator.AbstractProjectConfigurator;
import org.maven.ide.eclipse.project.configurator.ProjectConfigurationRequest;
import org.maven.ide.eclipse.wtp.filtering.ResourceFilteringBuildParticipant;


/**
 * Project configurator for WTP projects. Specific project configuration is delegated to the
 * IProjectConfiguratorDelegate bound to a maven packaging type.
 *
 * @author Igor Fedorenko
 */
public class WTPProjectConfigurator extends AbstractProjectConfigurator implements IExtendedJavaProjectConfigurator {

  @Override
  public void configure(ProjectConfigurationRequest request, IProgressMonitor monitor)
      throws CoreException {
    MavenProject mavenProject = request.getMavenProject();
    //Lookup the project configurator
    IProjectConfiguratorDelegate configuratorDelegate = ProjectConfiguratorDelegateFactory
        .getProjectConfiguratorDelegate(mavenProject.getPackaging());
    if(configuratorDelegate != null) {
      IProject project = request.getProject();
      if (project.getResourceAttributes().isReadOnly()){
        return;
      }

      try {
        configuratorDelegate.configureProject(project, mavenProject, monitor);
        configuratorDelegate.setModuleDependencies(project, mavenProject, monitor);
      } catch(MarkedException ex) {
        MavenLogger.log(ex.getMessage(), ex);
      }
    }
  }

  @Override
  public void mavenProjectChanged(MavenProjectChangedEvent event, IProgressMonitor monitor) throws CoreException {
    IMavenProjectFacade facade = event.getMavenProject();
    if(facade != null) {
      IProject project = facade.getProject();
      if (project.getResourceAttributes().isReadOnly()){
        return;
      }

      if(isWTPProject(project)) {
        MavenProject mavenProject = facade.getMavenProject(monitor);
        IProjectConfiguratorDelegate configuratorDelegate = ProjectConfiguratorDelegateFactory
            .getProjectConfiguratorDelegate(mavenProject.getPackaging());
        if(configuratorDelegate != null) {
          configuratorDelegate.setModuleDependencies(project, mavenProject, monitor);
        }
      }
    }
  }

  static boolean isWTPProject(IProject project) {
    return ModuleCoreNature.getModuleCoreNature(project) != null;
  }

  public Set<Artifact> resolveAdditionalArtifacts(IMavenProjectFacade facade, IProgressMonitor monitor) throws CoreException {
    MavenProject mavenProject = facade.getMavenProject(monitor);
    IProjectConfiguratorDelegate configuratorDelegate = ProjectConfiguratorDelegateFactory
        .getProjectConfiguratorDelegate(mavenProject.getPackaging());
    if(configuratorDelegate != null) {
      return configuratorDelegate.resolveAdditionalArtifacts(facade, monitor);
    }
    return null;
  }

  public ArtifactFilter getClasspathFilter(IMavenProjectFacade facade,
      IClasspathDescriptor classpath, IProgressMonitor monitor) throws CoreException {
    MavenProject mavenProject = facade.getMavenProject(monitor);
    IProjectConfiguratorDelegate configuratorDelegate = ProjectConfiguratorDelegateFactory
        .getProjectConfiguratorDelegate(mavenProject.getPackaging());
    if(configuratorDelegate != null) {
      return configuratorDelegate.getClasspathFilter(classpath);
    }
    return null;
  }

  public void configureClasspath(IMavenProjectFacade facade, IClasspathDescriptor classpath, IProgressMonitor monitor)
      throws CoreException {
    MavenProject mavenProject = facade.getMavenProject(monitor);
    //Lookup the project configurator
    IProjectConfiguratorDelegate configuratorDelegate = ProjectConfiguratorDelegateFactory
        .getProjectConfiguratorDelegate(mavenProject.getPackaging());
    if(configuratorDelegate != null) {
      IProject project = facade.getProject();
      try {
        configuratorDelegate.configureClasspath(project, mavenProject, classpath, monitor);
      } catch(CoreException ex) {
        MavenLogger.log(ex.getMessage(), ex);
      }
    }
  }

  public void configureRawClasspath(ProjectConfigurationRequest request, IClasspathDescriptor classpath,
      IProgressMonitor monitor) throws CoreException {
    // we do not change raw project classpath, do we?
  }

  /* (non-Javadoc)
   * @see org.maven.ide.eclipse.project.configurator.AbstractProjectConfigurator#getBuildParticipant(org.apache.maven.plugin.MojoExecution)
   */
  public AbstractBuildParticipant getBuildParticipant(MojoExecution execution) {
    return ResourceFilteringBuildParticipant.getParticipant(execution);
  }

}
