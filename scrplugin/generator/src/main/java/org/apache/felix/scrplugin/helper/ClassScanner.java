/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.scrplugin.helper;

import static org.objectweb.asm.ClassReader.SKIP_CODE;
import static org.objectweb.asm.ClassReader.SKIP_DEBUG;
import static org.objectweb.asm.ClassReader.SKIP_FRAMES;

import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.apache.felix.scrplugin.AnnotationProcessor;
import org.apache.felix.scrplugin.Log;
import org.apache.felix.scrplugin.Project;
import org.apache.felix.scrplugin.SCRDescriptorException;
import org.apache.felix.scrplugin.SCRDescriptorFailureException;
import org.apache.felix.scrplugin.description.ClassDescription;
import org.apache.felix.scrplugin.description.ComponentDescription;
import org.apache.felix.scrplugin.scanner.ClassAnnotation;
import org.apache.felix.scrplugin.scanner.FieldAnnotation;
import org.apache.felix.scrplugin.scanner.MethodAnnotation;
import org.apache.felix.scrplugin.scanner.ScannedAnnotation;
import org.apache.felix.scrplugin.scanner.ScannedClass;
import org.apache.felix.scrplugin.scanner.Source;
import org.apache.felix.scrplugin.xml.ComponentDescriptorIO;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The class scanner scans class files for annotations and invokes
 * the {@link AnnotationProcessor} on each scanned class file.
 */
public class ClassScanner {

    /**
     * The name of the Bundle manifest header providing the list of service
     * component descriptor files.
     */
    private static final String SERVICE_COMPONENT = "Service-Component";

    /**
     * The name of the file containing the scanned information from older
     * SCR generator versions.
     */
    private static final String ABSTRACT_DESCRIPTOR_ARCHIV_PATH = "OSGI-INF/scr-plugin/scrinfo.xml";

    /** Source for all generated descriptions. */
    private static final String GENERATED = "<generated>";

    /** Component descriptions loaded from dependencies*/
    private Map<String, ClassDescription> loadedDependencies;

    /** All used component descriptions. */
    private final Map<String, ClassDescription> allDescriptions;

    /** The log. */
    private final Log log;

    /** The issue log. */
    private final IssueLog iLog;

    /** The project. */
    private final Project project;

    /** The annotation processor. */
    private final AnnotationProcessor aProcessor;

    /**
     * Create a new scanner.
     */
    public ClassScanner(final Log log,
                    final IssueLog iLog,
                    final Project project,
                    final AnnotationProcessor aProcessor) {
        // create map for all descriptions and dummy entry for Object
        this.allDescriptions = new HashMap<String, ClassDescription>();
        allDescriptions.put(Object.class.getName(), new ClassDescription(Object.class, GENERATED));
        this.log = log;
        this.iLog = iLog;
        this.project = project;
        this.aProcessor = aProcessor;
    }

    /**
     * Scan all source class files for annotations and process them.
     */
    public List<ClassDescription> scanSources()
    throws SCRDescriptorException, SCRDescriptorFailureException {
        final List<ClassDescription> result = new ArrayList<ClassDescription>();

        for (final Source src : project.getSources()) {
            log.debug("Scanning class " + src.getClassName());

            try {
                // load the class
                final Class<?> annotatedClass = project.getClassLoader().loadClass(src.getClassName());

                final ClassDescription desc = this.processClass(annotatedClass, src.getFile().toString());
                if (desc != null) {
                    result.add(desc);
                    this.allDescriptions.put(annotatedClass.getName(), desc);
                } else {
                    this.allDescriptions.put(annotatedClass.getName(), new ClassDescription(annotatedClass, GENERATED));
                }
            } catch (final ClassNotFoundException cnfe) {
                throw new SCRDescriptorFailureException("Unable to load compiled class: " + src.getClassName(), cnfe);
            }
        }
        return result;
    }

    /**
     * Scan a single class.
     */
    private ClassDescription processClass(final Class<?> annotatedClass, final String location)
    throws SCRDescriptorFailureException, SCRDescriptorException {
        try {
            // get the class file for ASM
            final String pathToClassFile = annotatedClass.getName().replace('.', '/') + ".class";
            final InputStream input = project.getClassLoader().getResourceAsStream(pathToClassFile);
            final ClassReader classReader;
            try {
                classReader = new ClassReader(input);
            } finally {
                input.close();
            }
            final ClassNode classNode = new ClassNode();
            classReader.accept(classNode, SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES);

            // create descriptions
            final List<ScannedAnnotation> descriptions = extractAnnotation(classNode, annotatedClass);
            if (descriptions.size() > 0) {
                // process descriptions
                final ClassDescription desc = new ClassDescription(annotatedClass, location);
                aProcessor.process(new ScannedClass(descriptions, annotatedClass), desc);

                if (desc.getDescriptions(ComponentDescription.class).size() > 0) {
                    log.debug("Found component description " + desc + " in " + annotatedClass.getName());
                    return desc;
                }
            }
        } catch (final IOException ioe) {
            throw new SCRDescriptorFailureException("Unable to scan class files: " + annotatedClass.getName(), ioe);
        }
        return null;
    }

    /**
     * Extract annotations
     */
    private final List<ScannedAnnotation> extractAnnotation(final ClassNode classNode, final Class<?> annotatedClass)
                    throws SCRDescriptorException {
        final List<ScannedAnnotation> descriptions = new ArrayList<ScannedAnnotation>();
        // first parse class annotations
        @SuppressWarnings("unchecked")
        final List<AnnotationNode> annotations = classNode.invisibleAnnotations;
        if (annotations != null) {
            for (final AnnotationNode annotation : annotations) {
                this.parseAnnotation(descriptions, annotation, annotatedClass);
            }

            // second parse method annotations
            @SuppressWarnings("unchecked")
            final List<MethodNode> methods = classNode.methods;
            if (methods != null) {
                for (final MethodNode method : methods) {
                    @SuppressWarnings("unchecked")
                    final List<AnnotationNode> annos = method.invisibleAnnotations;
                    if (annos != null) {
                        final String name = method.name;
                        String signature = method.signature;
                        if (signature != null) {
                            // remove generics
                            int pos;
                            while ((pos = signature.indexOf('<')) > 0) {
                                final int lastPos = signature.indexOf('>');
                                signature = signature.substring(0, pos) + signature.substring(lastPos + 1);
                            }
                            // remove everthing after the brackets and replace slash with dot
                            pos = signature.lastIndexOf(')');
                            signature = signature.substring(0, pos + 1);
                            signature = signature.replace('/', '.');
                        }
                        final Method[] allMethods = annotatedClass.getDeclaredMethods();
                        Method found = null;
                        for (final Method m : allMethods) {
                            if (m.getName().equals(name)) {
                                if (m.getParameterTypes().length == 0 && signature == null) {
                                    found = m;
                                }
                                if (m.getParameterTypes().length > 0 && signature != null) {
                                    final StringBuilder sb = new StringBuilder("(");
                                    for (final Class<?> c : m.getParameterTypes()) {
                                        sb.append("L");
                                        sb.append(c.getName());
                                        sb.append(";");
                                    }
                                    sb.append(")");
                                    if (sb.toString().equals(signature)) {
                                        found = m;
                                    }
                                }
                            }
                        }
                        if (found == null) {
                            throw new SCRDescriptorException("Annotated method " + name + " not found.",
                                            annotatedClass.getName(), -1);
                        }
                        for (final AnnotationNode annotation : annos) {
                            parseAnnotation(descriptions, annotation, found);
                        }
                    }
                }
            }

            // third parse field annotations
            @SuppressWarnings("unchecked")
            final List<FieldNode> fields = classNode.fields;
            if (fields != null) {
                for (final FieldNode field : fields) {
                    @SuppressWarnings("unchecked")
                    final List<AnnotationNode> annos = field.invisibleAnnotations;
                    if (annos != null) {
                        final String name = field.name;
                        final Field[] allFields = annotatedClass.getDeclaredFields();
                        Field found = null;
                        for (final Field f : allFields) {
                            if (f.getName().equals(name)) {
                                found = f;
                            }
                        }
                        if (found == null) {
                            throw new SCRDescriptorException("Annotated field " + name + " not found.",
                                            annotatedClass.getName(), -1);
                        }
                        for (final AnnotationNode annotation : annos) {
                            parseAnnotation(descriptions, annotation, found);
                        }
                    }
                }
            }
        }
        return descriptions;
    }

    private <T> T[] convertToArray(final List<?> values, final Class<T> type) {
        @SuppressWarnings("unchecked")
        final T[] result = (T[]) Array.newInstance(type, values.size());
        return values.toArray(result);
    }

    /**
     * Parse annotation and create a description.
     */
    private void parseAnnotation(final List<ScannedAnnotation> descriptions, final AnnotationNode annotation,
                    final Object annotatedObject) {
        // desc has the format 'L' + className.replace('.', '/') + ';'
        final String name = annotation.desc.substring(1, annotation.desc.length() - 1).replace('/', '.');
        Map<String, Object> values = null;
        if (annotation.values != null) {
            values = new HashMap<String, Object>();
            final Iterator<?> i = annotation.values.iterator();
            while (i.hasNext()) {
                final Object vName = i.next();
                Object value = i.next();

                // convert type to class name string
                if (value instanceof Type) {
                    value = ((Type) value).getClassName();
                } else if (value instanceof List<?>) {
                    final List<?> objects = (List<?>) value;
                    if (objects.size() > 0) {
                        if (objects.get(0) instanceof Type) {
                            final String[] classNames = new String[objects.size()];
                            int index = 0;
                            for (final Object v : objects) {
                                classNames[index] = ((Type) v).getClassName();
                                index++;
                            }
                            value = classNames;
                        } else if (objects.get(0) instanceof AnnotationNode) {
                            final List<ScannedAnnotation> innerDesc = new ArrayList<ScannedAnnotation>();
                            for (final Object v : objects) {
                                parseAnnotation(innerDesc, (AnnotationNode) v, annotatedObject);
                            }
                            if (annotatedObject instanceof Method) {
                                value = innerDesc.toArray(new MethodAnnotation[innerDesc.size()]);
                            } else if (annotatedObject instanceof Field) {
                                value = innerDesc.toArray(new FieldAnnotation[innerDesc.size()]);
                            } else {
                                value = innerDesc.toArray(new ClassAnnotation[innerDesc.size()]);
                            }
                        } else {
                            value = convertToArray(objects, objects.get(0).getClass());
                        }
                    } else {
                        value = null;
                    }
                }

                values.put(vName.toString(), value);
            }
        }

        final ScannedAnnotation a;
        if (annotatedObject instanceof Method) {
            a = new MethodAnnotation(name, values, (Method) annotatedObject);
            ((Method) annotatedObject).setAccessible(true);
        } else if (annotatedObject instanceof Field) {
            a = new FieldAnnotation(name, values, (Field) annotatedObject);
            ((Field) annotatedObject).setAccessible(true);
        } else {
            a = new ClassAnnotation(name, values);
        }
        descriptions.add(a);
    }

    /**
     * Get a description for the class
     */
    public ClassDescription getDescription(final Class<?> clazz)
    throws SCRDescriptorException, SCRDescriptorFailureException {
        final String name = clazz.getName();
        ClassDescription result = this.allDescriptions.get(name);
        if ( result == null ) {
            // use scanner first
            result = this.processClass(clazz, GENERATED);

            if ( result == null ) {
                // now check loaded dependencies
                result = this.getComponentDescriptors().get(name);
            }

            // not found, create dummy
            if ( result == null ) {
                result = new ClassDescription(clazz, GENERATED);
            }

            // and cache
            allDescriptions.put(name, result);
        }
        return result;
    }

    /**
     * Returns a map of component descriptors which may be extended by the java
     * sources.
     * <p>
     * This method calls the {@link #getDependencies()} method and checks for
     * any Service-Component descriptors in the returned files.
     * <p>
     * This method may be overwritten by extensions of this class.
     *
     * @throws SCRDescriptorException May be thrown if an error occurrs
     *             gethering the component descriptors.
     */
    private Map<String, ClassDescription> getComponentDescriptors()
    throws SCRDescriptorException {
        if ( loadedDependencies == null ) {
            loadedDependencies = new HashMap<String, ClassDescription>();

            final Collection<File> dependencies = this.project.getDependencies();
            for ( final File artifact : dependencies ) {
                try {
                    this.log.debug( "Trying to get scrinfo from artifact " + artifact );
                    // First try to read the private scr info file from previous scr generator versions
                    InputStream scrInfoFile = null;
                    try {
                        scrInfoFile = this.getFile( artifact, ABSTRACT_DESCRIPTOR_ARCHIV_PATH );
                        if ( scrInfoFile != null ) {
                            final List<ClassDescription> c =  this.parseServiceComponentDescriptor( scrInfoFile, artifact.toString() + ':' + ABSTRACT_DESCRIPTOR_ARCHIV_PATH);
                            if ( c != null ) {
                                for(final ClassDescription cd : c) {
                                    loadedDependencies.put(cd.getDescribedClass().getName(), cd);
                                }
                            }
                            continue;
                        }
                        this.log.debug( "Artifact has no scrinfo file (it's optional): " + artifact );
                    } catch ( final IOException ioe ) {
                        throw new SCRDescriptorException( "Unable to get scrinfo from artifact", artifact.toString(), 0,
                            ioe );
                    } finally {
                        if ( scrInfoFile != null ) {
                            try { scrInfoFile.close(); } catch ( final IOException ignore ) {}
                        }
                    }

                    this.log.debug( "Trying to get manifest from artifact " + artifact );
                    final Manifest manifest = this.getManifest( artifact );
                    if ( manifest != null ) {
                        // read Service-Component entry
                        if ( manifest.getMainAttributes().getValue( SERVICE_COMPONENT ) != null ) {
                            final String serviceComponent = manifest.getMainAttributes().getValue(SERVICE_COMPONENT );
                            this.log.debug( "Found Service-Component: " + serviceComponent + " in artifact " + artifact );
                            final StringTokenizer st = new StringTokenizer( serviceComponent, "," );
                            while ( st.hasMoreTokens() ) {
                                final String entry = st.nextToken().trim();
                                if ( entry.length() > 0 ) {
                                    final List<ClassDescription> c = this.readServiceComponentDescriptor( artifact, entry );
                                    if ( c != null ) {
                                        for(final ClassDescription cd : c) {
                                            loadedDependencies.put(cd.getDescribedClass().getName(), cd);
                                        }
                                    }
                                }
                            }
                        } else {
                            this.log.debug( "Artifact has no service component entry in manifest " + artifact );
                        }
                    } else {
                        this.log.debug( "Unable to get manifest from artifact " + artifact );
                    }
                } catch ( IOException ioe ) {
                    throw new SCRDescriptorException( "Unable to get manifest from artifact", artifact.toString(), 0,
                        ioe );
                }

            }
        }
        return this.loadedDependencies;
    }

    /**
     * Parses the descriptors read from the given input stream. This method may
     * be called by the {@link #getComponentDescriptors()} method to parse the
     * descriptors gathered in an implementation dependent way.
     *
     * @throws SCRDescriptorException If an error occurrs reading the
     *             descriptors from the stream.
     */
    private List<ClassDescription> parseServiceComponentDescriptor(
                    final InputStream file, final String location )
    throws SCRDescriptorException {
        return ComponentDescriptorIO.read( file, this.project.getClassLoader(), iLog, location );
    }

    /**
     * Read the service component description.
     *
     * @param artifact
     * @param entry
     * @throws IOException
     * @throws SCRDescriptorException
     */
    private List<ClassDescription> readServiceComponentDescriptor( final File artifactFile, String entry ) {
        this.log.debug( "Reading " + entry + " from " + artifactFile );
        InputStream xml = null;
        try {
            xml = this.getFile( artifactFile, entry );
            if ( xml == null ) {
                throw new SCRDescriptorException( "Entry " + entry + " not contained in JAR File ", artifactFile.toString(),
                    0 );
            }
            return this.parseServiceComponentDescriptor( xml, artifactFile.toString() + ':' + entry );
        } catch ( final IOException mee ) {
            this.log.warn( "Unable to read SCR descriptor file from JAR File " + artifactFile + " at " + entry );
            this.log.debug( "Exception occurred during reading: " + mee.getMessage(), mee );
        } catch ( final SCRDescriptorException mee ) {
            this.log.warn( "Unable to read SCR descriptor file from JAR File " + artifactFile + " at " + entry );
            this.log.debug( "Exception occurred during reading: " + mee.getMessage(), mee );
        } finally {
            if ( xml != null ) {
                try { xml.close(); } catch (final IOException ignore) {}
            }
        }

        return null;
    }


    /**
     * Get the manifest from the artifact.
     * The artifact can either be a jar or a directory.
     */
    private Manifest getManifest( final File artifact ) throws IOException {
        if ( artifact.isDirectory() ) {
            // this is maybe a classes directory, try to read manifest file directly
            final File dir = new File(artifact, "META-INF");
            if ( !dir.exists() || !dir.isDirectory() ) {
                return null;
            }
            final File mf = new File(dir, "MANIFEST.MF");
            if ( !mf.exists() || !mf.isFile() ) {
                return null;
            }
            final InputStream is = new FileInputStream(mf);
            try {
                return new Manifest(is);
            } finally {
                try { is.close(); } catch (final IOException ignore) { }
            }
        }
        JarFile file = null;
        try {
            file = new JarFile( artifact );
            return file.getManifest();
        } finally {
            if ( file != null ) {
                try { file.close(); } catch ( final IOException ignore ) {}
            }
        }
    }

    private InputStream getFile( final File artifactFile, final String path ) throws IOException {
        if ( artifactFile.isDirectory() ) {
            final String filePath = path.replace('/', File.separatorChar).replace('\\', File.separatorChar);
            final File file = new File(artifactFile, filePath);
            if ( file.exists() && file.isFile() ) {
                return new FileInputStream(file);
            }
            return null;
        }
        JarFile file = null;
        try {
            file = new JarFile( artifactFile );
            final JarEntry entry = file.getJarEntry( path );
            if ( entry != null ) {
                final InputStream stream = new ArtifactFileInputStream( file, entry );
                file = null; // prevent file from being closed now
                return stream;
            }
            return null;
        } finally {
            if ( file != null ) {
                try { file.close(); } catch ( final IOException ignore ) {}
            }
        }
    }

    private static class ArtifactFileInputStream extends FilterInputStream {

        final JarFile jarFile;

        ArtifactFileInputStream( JarFile jarFile, JarEntry jarEntry ) throws IOException {
            super( jarFile.getInputStream( jarEntry ) );
            this.jarFile = jarFile;
        }


        @Override
        public void close() throws IOException {
            try {
                super.close();
            } catch ( final IOException ioe ) {
                // ignore
            }
            jarFile.close();
        }


        @Override
        protected void finalize() throws Throwable {
            try {
                close();
            } finally {
                super.finalize();
            }
        }
    }
}
