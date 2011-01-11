/*******************************************************************************
 * Copyright (c) 2008 Sonatype, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

package org.maven.ide.eclipse.wtp;

import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.project.MavenProject;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jst.j2ee.classpathdep.IClasspathDependencyConstants;
import org.eclipse.wst.common.componentcore.ComponentCore;
import org.eclipse.wst.common.componentcore.ModuleCoreNature;
import org.eclipse.wst.common.componentcore.resources.IVirtualComponent;
import org.eclipse.wst.common.componentcore.resources.IVirtualFolder;
import org.maven.ide.eclipse.jdt.IClasspathDescriptor;
import org.maven.ide.eclipse.project.MavenProjectUtils;

/**
 * WarOverlayProjectConfiguratorDelegate
 */
class WarOverlayProjectConfiguratorDelegate extends AbstractProjectConfiguratorDelegate {

  /* (non-Javadoc)
   * @see org.maven.ide.eclipse.wtp.AbstractProjectConfiguratorDelegate#configure(org.eclipse.core.resources.IProject, org.apache.maven.project.MavenProject, org.eclipse.core.runtime.IProgressMonitor)
   */
  protected void configure(IProject project, MavenProject mavenProject, IProgressMonitor monitor) throws CoreException {
    //Configure war-overlays as utility projects
    configureWtpUtil(project, mavenProject, monitor);

    //Reconfigure the project
    IVirtualComponent component = ComponentCore.createComponent(project);
    if(component != null) {
      ModuleCoreNature.addModuleCoreNatureIfNecessary(project, monitor);
      IVirtualFolder rootVirtualFolder = component.getRootFolder().getFolder("/");

      //Remove all the default links
      final IJavaProject javaProject = JavaCore.create(project);
      final IClasspathEntry[] cp = javaProject.getRawClasspath();
      for(int i = 0; i < cp.length; i++ ) {
        final IClasspathEntry cpe = cp[i];
        if(cpe.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
          IPath path = cpe.getPath().removeFirstSegments(1);
          if(path.isEmpty()) {
            path = new Path("/"); //$NON-NLS-1$
          }
          rootVirtualFolder.removeLink(path, 0, null);
        }
      }

      if(ModuleCoreNature.getModuleCoreNature(project) != null) {
        //Add a link to the source
        WarPluginConfiguration config = new WarPluginConfiguration(mavenProject, project);
        String warSourceDirectory = config.getWarSourceDirectory();
        rootVirtualFolder.createLink(new Path(warSourceDirectory), 0, monitor);

        //Add a link to the classes
        IPath outputLocationPath = MavenProjectUtils.getProjectRelativePath(project, mavenProject.getBuild()
            .getOutputDirectory());
        //this.outputLocation = (path != null) ? fullPath.append(path) : null;
        IVirtualFolder webInfClassesFolder = rootVirtualFolder
            .getFolder(IClasspathDependencyConstants.WEB_INF_CLASSES_PATH);
        webInfClassesFolder.createLink(outputLocationPath, 0, monitor);
      }
    }
  }

  /* (non-Javadoc)
   * @see org.maven.ide.eclipse.wtp.AbstractProjectConfiguratorDelegate#getClasspathFilter(org.maven.ide.eclipse.jdt.IClasspathDescriptor)
   */
  public ArtifactFilter getClasspathFilter(IClasspathDescriptor classpath) {
    return new WarOverlayHandledByProjectFilter(classpath);
  }

  /* (non-Javadoc)
   * @see org.maven.ide.eclipse.wtp.IProjectConfiguratorDelegate#setModuleDependencies(org.eclipse.core.resources.IProject, org.apache.maven.project.MavenProject, org.eclipse.core.runtime.IProgressMonitor)
   */
  public void setModuleDependencies(IProject project, MavenProject mavenProject, IProgressMonitor monitor)
      throws CoreException {
    // do nothing
  }

  /* (non-Javadoc)
   * @see org.maven.ide.eclipse.wtp.IProjectConfiguratorDelegate#configureClasspath(org.eclipse.core.resources.IProject, org.apache.maven.project.MavenProject, org.maven.ide.eclipse.jdt.IClasspathDescriptor, org.eclipse.core.runtime.IProgressMonitor)
   */
  public void configureClasspath(IProject project, MavenProject mavenProject, IClasspathDescriptor classpath,
      IProgressMonitor monitor) throws CoreException {
    // do nothing
  }

}