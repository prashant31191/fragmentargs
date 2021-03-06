package com.hannesdorfmann.fragmentargs.processor;

import com.hannesdorfmann.fragmentargs.FragmentArgs;
import com.hannesdorfmann.fragmentargs.FragmentArgsInjector;
import com.hannesdorfmann.fragmentargs.annotation.Arg;
import com.hannesdorfmann.fragmentargs.annotation.FragmentArgsInherited;
import com.hannesdorfmann.fragmentargs.bundler.ArgsBundler;
import com.hannesdorfmann.fragmentargs.repacked.com.squareup.javawriter.JavaWriter;
import java.io.IOException;
import java.io.Serializable;
import java.io.Writer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * This is the annotation processor for FragmentArgs
 *
 * @author Hannes Dorfmann
 */
public class ArgProcessor extends AbstractProcessor {

  private static final Map<String, String> ARGUMENT_TYPES = new HashMap<String, String>(20);

  /**
   * Annotation Processor Option
   */
  private static final String OPTION_IS_LIBRARY = "fragmentArgsLib";

  static {
    ARGUMENT_TYPES.put("java.lang.String", "String");
    ARGUMENT_TYPES.put("int", "Int");
    ARGUMENT_TYPES.put("java.lang.Integer", "Int");
    ARGUMENT_TYPES.put("long", "Long");
    ARGUMENT_TYPES.put("java.lang.Long", "Long");
    ARGUMENT_TYPES.put("double", "Double");
    ARGUMENT_TYPES.put("java.lang.Double", "Double");
    ARGUMENT_TYPES.put("short", "Short");
    ARGUMENT_TYPES.put("java.lang.Short", "Short");
    ARGUMENT_TYPES.put("float", "Float");
    ARGUMENT_TYPES.put("java.lang.Float", "Float");
    ARGUMENT_TYPES.put("byte", "Byte");
    ARGUMENT_TYPES.put("java.lang.Byte", "Byte");
    ARGUMENT_TYPES.put("boolean", "Boolean");
    ARGUMENT_TYPES.put("java.lang.Boolean", "Boolean");
    ARGUMENT_TYPES.put("char", "Char");
    ARGUMENT_TYPES.put("java.lang.Character", "Char");
    ARGUMENT_TYPES.put("java.lang.CharSequence", "CharSequence");
    ARGUMENT_TYPES.put("android.os.Bundle", "Bundle");
    ARGUMENT_TYPES.put("android.os.Parcelable", "Parcelable");
  }

  private Elements elementUtils;
  private Types typeUtils;
  private Filer filer;

  @Override public Set<String> getSupportedAnnotationTypes() {
    Set<String> supportTypes = new LinkedHashSet<String>();
    supportTypes.add(Arg.class.getCanonicalName());
    supportTypes.add(FragmentArgsInherited.class.getCanonicalName());
    return supportTypes;
  }

  @Override public Set<String> getSupportedOptions() {
    Set<String> suppotedOptions = new LinkedHashSet<String>();
    suppotedOptions.add(OPTION_IS_LIBRARY);
    return suppotedOptions;
  }

  @Override public synchronized void init(ProcessingEnvironment env) {
    super.init(env);

    elementUtils = env.getElementUtils();
    typeUtils = env.getTypeUtils();
    filer = env.getFiler();
  }

  protected String getOperation(ArgumentAnnotatedField arg) {
    String op = ARGUMENT_TYPES.get(arg.getRawType());
    if (op != null) {
      if (arg.isArray()) {
        return op + "Array";
      } else {
        return op;
      }
    }

    Elements elements = processingEnv.getElementUtils();
    TypeMirror type = arg.getElement().asType();
    Types types = processingEnv.getTypeUtils();
    String[] arrayListTypes = new String[] {
        String.class.getName(), Integer.class.getName(), CharSequence.class.getName()
    };
    String[] arrayListOps =
        new String[] { "StringArrayList", "IntegerArrayList", "CharSequenceArrayList" };
    for (int i = 0; i < arrayListTypes.length; i++) {
      TypeMirror tm = getArrayListType(arrayListTypes[i]);
      if (types.isAssignable(type, tm)) {
        return arrayListOps[i];
      }
    }

    if (types.isAssignable(type,
        getWildcardType(ArrayList.class.getName(), "android.os.Parcelable"))) {
      return "ParcelableArrayList";
    }
    TypeMirror sparseParcelableArray =
        getWildcardType("android.util.SparseArray", "android.os.Parcelable");

    if (types.isAssignable(type, sparseParcelableArray)) {
      return "SparseParcelableArray";
    }

    if (types.isAssignable(type, elements.getTypeElement(Serializable.class.getName()).asType())) {
      return "Serializable";
    }

    if (types.isAssignable(type, elements.getTypeElement("android.os.Parcelable").asType())) {
      return "Parcelable";
    }

    return null;
  }

  private TypeMirror getWildcardType(String type, String elementType) {
    TypeElement arrayList = processingEnv.getElementUtils().getTypeElement(type);
    TypeMirror elType = processingEnv.getElementUtils().getTypeElement(elementType).asType();
    return processingEnv.getTypeUtils()
        .getDeclaredType(arrayList, processingEnv.getTypeUtils().getWildcardType(elType, null));
  }

  private TypeMirror getArrayListType(String elementType) {
    TypeElement arrayList = processingEnv.getElementUtils().getTypeElement("java.util.ArrayList");
    TypeMirror elType = processingEnv.getElementUtils().getTypeElement(elementType).asType();
    return processingEnv.getTypeUtils().getDeclaredType(arrayList, elType);
  }

  protected void writePutArguments(JavaWriter jw, String sourceVariable, String bundleVariable,
      ArgumentAnnotatedField arg) throws IOException, ProcessingException {

    jw.emitEmptyLine();

    if (arg.hasCustomBundler()) {
      jw.emitStatement("%s.put(\"%s\", %s, %s)", arg.getBundlerFieldName(), arg.getKey(),
          sourceVariable, bundleVariable);
    } else {

      String op = getOperation(arg);

      if (op == null) {
        throw new ProcessingException(arg.getElement(),
            "Don't know how to put %s in a Bundle. This type is not supported by default. "
                + "However, you can specify your own %s implementation in @Arg( bundler = YourBundler.class)",
            arg.getElement().asType().toString(), ArgsBundler.class.getSimpleName());
      }
      if ("Serializable".equals(op)) {
        processingEnv.getMessager()
            .printMessage(Diagnostic.Kind.WARNING,
                String.format("%1$s will be stored as Serializable", arg.getName()),
                arg.getElement());
      }
      jw.emitStatement("%4$s.put%1$s(\"%2$s\", %3$s)", op, arg.getKey(), sourceVariable,
          bundleVariable);
    }
  }

  protected void writePackage(JavaWriter jw, TypeElement type) throws IOException {
    PackageElement pkg = processingEnv.getElementUtils().getPackageOf(type);
    if (!pkg.isUnnamed()) {
      jw.emitPackage(pkg.getQualifiedName().toString());
    } else {
      jw.emitPackage("");
    }
  }

  /**
   * Scans for @Arg annotations in the class itself and all super classes (complete inheritance
   * hierarchy)
   */
  private AnnotatedFragment getAllInclSuperClasses(TypeElement type) throws ProcessingException {

    AnnotatedFragment fragment = new AnnotatedFragment(type);
    TypeElement currentClass = type;
    do {

      for (Element e : currentClass.getEnclosedElements()) {
        if (e.getKind() != ElementKind.FIELD) {
          continue;
        }

        // It's a field
        Arg annotation = null;
        if ((annotation = e.getAnnotation(Arg.class)) != null) {
          ArgumentAnnotatedField annotatedField =
              new ArgumentAnnotatedField(e, (TypeElement) e.getEnclosingElement(), annotation);
          addAnnotatedField(annotatedField, fragment, annotation);
        }
      }

      TypeMirror superClassType = currentClass.getSuperclass();
      if (superClassType.getKind() == TypeKind.NONE) {
        // Basis class (java.lang.Object) reached, so exit
        currentClass = null;
        break;
      } else {
        currentClass = (TypeElement) typeUtils.asElement(superClassType);
      }
    } while (currentClass != null);

    return fragment;
  }

  /**
   * Generates an error String with detailed information about that a field with the same name is
   * already defined in a super class
   */
  private String getErrorMessageDuplicatedField(AnnotatedFragment fragment,
      TypeElement problemClass, String fieldName) {

    String base =
        "A field with the name '%s' in class %s is already annotated with @%s in super class %s ! "
            + "Fields name must be unique within inheritance hierarchy.";

    // Assumption: The problemClass is already a super class of the real problem,
    // So determine the real problem by searching for the subclass that cause this problem
    TypeElement otherClass = null;
    for (ArgumentAnnotatedField otherField : fragment.getAll()) {
      if (otherField.getVariableName().equals(fieldName)) {
        otherClass = otherField.getClassElement();
        break;
      }
    }

    if (otherClass != null) {
      // Check who is the super class
      TypeElement currentClass = otherClass;

      while (currentClass != null) {
        TypeMirror currentClassSuperclass = currentClass.getSuperclass();
        if (currentClassSuperclass == null || currentClassSuperclass.getKind() == TypeKind.NONE) {
          // They are not super classes
          break;
        }

        if (currentClass.getQualifiedName() != null && currentClass.getQualifiedName()
            .toString()
            .equals(problemClass.getQualifiedName().toString())) {
          // The problem causing class is a super class, so we found the superclass
          // and the sub class that cause the problem
          return String.format(base, fieldName, otherClass.getQualifiedName().toString(),
              Arg.class.getSimpleName(), problemClass.getQualifiedName());
        }

        currentClass = (TypeElement) typeUtils.asElement(currentClassSuperclass);
      }
    }

    // Since the previous check wasn't successfull we can assume:
    // The problemClass must be a sub class, so find the super class that contains the field
    TypeMirror superClass = problemClass.getSuperclass();
    TypeElement superClassElement = null;

    if (superClass == null) {
      return String.format(
          "A field with the name '%s' in class %s is already annotated with @%s in a super class or sub class! "
              + "Fields name must be unique within inheritance hierarchy.", fieldName,
          problemClass.getQualifiedName().toString(), Arg.class.getSimpleName());
    }

    boolean superClassFound = false;
    while (superClass != null
        && superClass.getKind() != TypeKind.NONE
        && (superClassElement = (TypeElement) typeUtils.asElement(superClass)) != null) {

      for (Element e : superClassElement.getEnclosedElements()) {
        if (e.getKind() == ElementKind.FIELD && e.getSimpleName() != null &&
            e.getSimpleName().toString() != null && e.getSimpleName()
            .toString()
            .equals(fieldName)) {
          superClassFound = true;
          break;
        }
      }

      if (superClassFound) {
        break;
      }
      superClass = superClassElement.getSuperclass();
    }

    if (superClassElement == null) {
      // Should never be the case, however to ensure we return a error message without superclass
      return String.format(
          "A field with the name '%s' in class %s is already annotated with @%s in a "
              + "super class or sub class of %s ! "
              + "Fields name must be unique within inheritance hierarchy.", fieldName,
          problemClass.getQualifiedName().toString(), Arg.class.getSimpleName(),
          problemClass.getQualifiedName().toString());
    }

    return String.format(base, fieldName, problemClass.getQualifiedName().toString(),
        Arg.class.getSimpleName(), superClassElement.getQualifiedName());
  }

  /**
   * Checks if the annotated field can be added to the given fragment. Otherwise a error message
   * will be printed
   */
  private void addAnnotatedField(ArgumentAnnotatedField annotatedField, AnnotatedFragment fragment,
      Arg annotation) throws ProcessingException {

    if (fragment.containsField(annotatedField)) {
      // A field already with the name is here
      throw new ProcessingException(annotatedField.getElement(),
          getErrorMessageDuplicatedField(fragment, annotatedField.getClassElement(),
              annotatedField.getVariableName()));
    } else if (fragment.containsBundleKey(annotatedField) != null) {
      //  key for bundle is already in use
      ArgumentAnnotatedField otherField = fragment.containsBundleKey(annotatedField);
      throw new ProcessingException(annotatedField.getElement(),
          "The bundle key '%s' for field %s in %s is already used by another "
              + "argument in %s (field name is '%s'). Bundle keys must be unique in inheritance hierarchy!",
          annotatedField.getKey(), annotatedField.getVariableName(),
          annotatedField.getClassElement().getQualifiedName().toString(),
          otherField.getClassElement().getQualifiedName().toString(), otherField.getVariableName());
    } else {
      if (annotation.required()) {
        fragment.addRequired(annotatedField);
      } else {
        fragment.addOptional(annotatedField);
      }
    }
  }

  /**
   * Collects the fields that are annotated by the fragmentarg
   */
  private AnnotatedFragment collectArgumentsForType(TypeElement type) throws ProcessingException {

    boolean superClasses = true;
    FragmentArgsInherited inheritedFragmentArgs = type.getAnnotation(FragmentArgsInherited.class);
    if (inheritedFragmentArgs != null) {
      superClasses = inheritedFragmentArgs.value();
    }

    // incl. super classes
    if (superClasses) {
      return getAllInclSuperClasses(type);
    }

    // Without super classes (inheritance)
    AnnotatedFragment fragment = new AnnotatedFragment(type);
    for (Element element : type.getEnclosedElements()) {
      if (element.getKind() == ElementKind.FIELD) {
        Arg annotation = element.getAnnotation(Arg.class);
        if (annotation != null) {
          ArgumentAnnotatedField field = new ArgumentAnnotatedField(element, type, annotation);
          addAnnotatedField(field, fragment, annotation);
        }
      }
    }
    return fragment;
  }

  @Override public boolean process(Set<? extends TypeElement> type, RoundEnvironment env) {

    Elements elementUtils = processingEnv.getElementUtils();
    Types typeUtils = processingEnv.getTypeUtils();
    Filer filer = processingEnv.getFiler();

    boolean isLibrary = false;
    String fragmentArgsLib = processingEnv.getOptions().get(OPTION_IS_LIBRARY);
    if (fragmentArgsLib != null && fragmentArgsLib.equalsIgnoreCase("true")) {
      isLibrary = true;
    }

    JavaWriter jw = null;

    try { // Catch processing exceptions

      TypeElement fragmentType = elementUtils.getTypeElement("android.app.Fragment");
      TypeElement supportFragmentType =
          elementUtils.getTypeElement("android.support.v4.app.Fragment");

      // REMEMBER: It's a SET! it uses .equals() .hashCode() to determine if element already in set
      Set<TypeElement> fragmentClasses = new HashSet<TypeElement>();

      Element[] origHelper = null;

      // Search for @Arg fields
      for (Element element : env.getElementsAnnotatedWith(Arg.class)) {
        TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();

        // Check if its a fragment
        if (!isFragmentClass(enclosingElement, fragmentType, supportFragmentType)) {
          throw new ProcessingException(element, "@Arg can only be used on fragment fields (%s.%s)",
              enclosingElement.getQualifiedName(), element);
        }

        if (element.getModifiers().contains(Modifier.FINAL)
            || element.getModifiers()
            .contains(Modifier.STATIC)
            || element.getModifiers().contains(Modifier.PRIVATE)
            || element.getModifiers().contains(Modifier.PROTECTED)) {

          throw new ProcessingException(element,
              "@Arg fields must not be private, protected, final or static (%s.%s)",
              enclosingElement.getQualifiedName(), element);
        }

        // Skip abstract classes
        if (!enclosingElement.getModifiers().contains(Modifier.ABSTRACT)) {
          fragmentClasses.add(enclosingElement);
        }
      }

      // Search for "just" @InheritedFragmentArgs
      for (Element element : env.getElementsAnnotatedWith(FragmentArgsInherited.class)) {

        if (element.getKind() != ElementKind.CLASS) {
          throw new ProcessingException(element, "%s can only be applied on Fragment classes",
              FragmentArgsInherited.class.getSimpleName());
        }

        TypeElement classElement = (TypeElement) element;

        // Check if its a fragment
        if (!isFragmentClass(element, fragmentType, supportFragmentType)) {
          throw new ProcessingException(element,
              "%s can only be used on fragments, but %s is not a subclass of fragment",
              FragmentArgsInherited.class.getSimpleName(), classElement.getQualifiedName());
        }

        // Skip abstract classes
        if (!classElement.getModifiers().contains(Modifier.ABSTRACT)) {
          fragmentClasses.add(classElement);
        }
      }

      // Store the key - value for the generated FragmentArtMap class
      Map<String, String> autoMapping = new HashMap<String, String>();

      for (TypeElement fragmentClass : fragmentClasses) {
        try {

          AnnotatedFragment fragment = collectArgumentsForType(fragmentClass);

          String builder = fragment.getSimpleName() + "Builder";
          List<Element> originating = new ArrayList<Element>(10);
          originating.add(fragmentClass);
          TypeMirror superClass = fragmentClass.getSuperclass();
          while (superClass.getKind() != TypeKind.NONE) {
            TypeElement element = (TypeElement) typeUtils.asElement(superClass);
            if (element.getQualifiedName().toString().startsWith("android.")) {
              break;
            }
            originating.add(element);
            superClass = element.getSuperclass();
          }

          String qualifiedFragmentName = fragment.getQualifiedName().toString();
          String qualifiedBuilderName = qualifiedFragmentName + "Builder";

          Element[] orig = originating.toArray(new Element[originating.size()]);
          origHelper = orig;

          JavaFileObject jfo = filer.createSourceFile(qualifiedBuilderName, orig);
          Writer writer = jfo.openWriter();
          jw = new JavaWriter(writer);
          writePackage(jw, fragmentClass);
          jw.emitImports("android.os.Bundle");
          jw.emitEmptyLine();
          jw.beginType(builder, "class", EnumSet.of(Modifier.PUBLIC, Modifier.FINAL));

          if (!fragment.getBundlerVariableMap().isEmpty()) {
            jw.emitEmptyLine();
            for (Map.Entry<String, String> e : fragment.getBundlerVariableMap().entrySet()) {
              jw.emitField(e.getKey(), e.getValue(),
                  EnumSet.of(Modifier.PRIVATE, Modifier.FINAL, Modifier.STATIC),
                  "new " + e.getKey() + "()");
            }
          }
          jw.emitEmptyLine();
          jw.emitField("Bundle", "mArguments", EnumSet.of(Modifier.PRIVATE, Modifier.FINAL),
              "new Bundle()");
          jw.emitEmptyLine();

          Set<ArgumentAnnotatedField> required = fragment.getRequiredFields();

          String[] args = new String[required.size() * 2];
          int index = 0;

          for (ArgumentAnnotatedField arg : required) {
            args[index++] = arg.getType();
            args[index++] = arg.getVariableName();
          }
          jw.beginMethod(null, builder, EnumSet.of(Modifier.PUBLIC), args);

          for (ArgumentAnnotatedField arg : required) {
            writePutArguments(jw, arg.getVariableName(), "mArguments", arg);
          }

          jw.endMethod();

          if (!required.isEmpty()) {
            jw.emitEmptyLine();
            writeNewFragmentWithRequiredMethod(builder, fragmentClass, jw, args);
          }

          Set<ArgumentAnnotatedField> optionalArguments = fragment.getOptionalFields();

          for (ArgumentAnnotatedField arg : optionalArguments) {
            writeBuilderMethod(builder, jw, arg);
          }

          jw.emitEmptyLine();
          writeInjectMethod(jw, fragmentClass, fragment.getAll());

          jw.emitEmptyLine();
          writeBuildMethod(jw, fragmentClass);

          jw.emitEmptyLine();
          writeBuildSubclassMethod(jw, fragmentClass);
          jw.endType();
          jw.close();

          autoMapping.put(qualifiedFragmentName, qualifiedBuilderName);
        } catch (IOException e) {
          throw new ProcessingException(fragmentClass, "Unable to write builder for type %s: %s",
              fragmentClass, e.getMessage());
        }
      }

      // Write the automapping class
      if (origHelper != null && !isLibrary) {
        writeAutoMapping(autoMapping, origHelper);
      }
    } catch (ProcessingException e) {
      // print error
      if (jw != null) {

        // Do cleanup if not closed yet
        try {
          jw.close();
        } catch (Exception catched) {
        }
      }

      error(e);
    }

    return true;
  }

  /**
   * Checks if the given element is in a valid Fragment class
   */
  private boolean isFragmentClass(Element classElement, TypeElement fragmentType,
      TypeElement supportFragmentType) {

    return (fragmentType != null && typeUtils.isSubtype(classElement.asType(),
        fragmentType.asType())) || (supportFragmentType != null && typeUtils.isSubtype(
        classElement.asType(), supportFragmentType.asType()));
  }

  /**
   * Key is the fully qualified fragment name, value is the fully qualified Builder class name
   */

  private void writeAutoMapping(Map<String, String> mapping, Element[] element)
      throws ProcessingException {

    try {
      JavaFileObject jfo =
          filer.createSourceFile(FragmentArgs.AUTO_MAPPING_QUALIFIED_CLASS, element);
      Writer writer = jfo.openWriter();
      JavaWriter jw = new JavaWriter(writer);
      // Package
      jw.emitPackage(FragmentArgs.AUTO_MAPPING_PACKAGE);
      // Imports
      jw.emitImports("android.os.Bundle");

      // Class
      jw.beginType(FragmentArgs.AUTO_MAPPING_CLASS_NAME, "class",
          EnumSet.of(Modifier.PUBLIC, Modifier.FINAL), null,
          FragmentArgsInjector.class.getCanonicalName());

      jw.emitEmptyLine();
      // The mapping Method
      jw.beginMethod("void", "inject", EnumSet.of(Modifier.PUBLIC), "Object", "target");

      jw.emitEmptyLine();
      jw.emitStatement("Class<?> targetClass = target.getClass()");
      jw.emitStatement("String targetName = targetClass.getCanonicalName()");

      for (Map.Entry<String, String> entry : mapping.entrySet()) {

        jw.emitEmptyLine();
        jw.beginControlFlow("if ( %s.class.getName().equals(targetName) )", entry.getKey());
        jw.emitStatement("%s.injectArguments( ( %s ) target)", entry.getValue(), entry.getKey());
        jw.emitStatement("return");
        jw.endControlFlow();
      }

      // End Mapping method
      jw.endMethod();

      jw.endType();
      jw.close();
    } catch (IOException e) {
      throw new ProcessingException(null,
          "Unable to write the automapping class for builder to fragment: %s: %s",
          FragmentArgs.AUTO_MAPPING_QUALIFIED_CLASS, e.getMessage());
    }
  }

  private void writeNewFragmentWithRequiredMethod(String builder, TypeElement element,
      JavaWriter jw, String[] args) throws IOException {
    jw.beginMethod(element.getQualifiedName().toString(), "new" + element.getSimpleName(),
        EnumSet.of(Modifier.STATIC, Modifier.PUBLIC), args);
    StringBuilder argNames = new StringBuilder();
    for (int i = 1; i < args.length; i += 2) {
      argNames.append(args[i]);
      if (i < args.length - 1) {
        argNames.append(", ");
      }
    }
    jw.emitStatement("return new %1$s(%2$s).build()", builder, argNames);
    jw.endMethod();
  }

  private void writeBuildMethod(JavaWriter jw, TypeElement element) throws IOException {
    jw.beginMethod(element.getSimpleName().toString(), "build", EnumSet.of(Modifier.PUBLIC));
    jw.emitStatement("%1$s fragment = new %1$s()", element.getSimpleName().toString());
    jw.emitStatement("fragment.setArguments(mArguments)");
    jw.emitStatement("return fragment");
    jw.endMethod();
  }

  private void writeBuildSubclassMethod(JavaWriter jw, TypeElement element) throws IOException {
    jw.beginMethod("<F extends " + element.getSimpleName().toString() + "> F", "build",
        EnumSet.of(Modifier.PUBLIC), "F", "fragment");
    jw.emitStatement("fragment.setArguments(mArguments)");
    jw.emitStatement("return fragment");
    jw.endMethod();
  }

  private void writeInjectMethod(JavaWriter jw, TypeElement element,
      Set<ArgumentAnnotatedField> allArguments) throws IOException, ProcessingException {
    jw.beginMethod("void", "injectArguments",
        EnumSet.of(Modifier.PUBLIC, Modifier.FINAL, Modifier.STATIC),
        element.getSimpleName().toString(), "fragment");

    jw.emitStatement("Bundle args = fragment.getArguments()");
    jw.beginControlFlow("if (args == null)");
    jw.emitStatement("throw new IllegalStateException(\"No arguments set\")");
    jw.endControlFlow();

    for (ArgumentAnnotatedField type : allArguments) {
      jw.emitEmptyLine();

      // Args Bundler
      if (type.hasCustomBundler()) {

        // Required
        if (type.isRequired()) {
          jw.beginControlFlow(
              "if (!args.containsKey(" + JavaWriter.stringLiteral(type.getKey()) + "))");
          jw.emitStatement("throw new IllegalStateException(\"required argument %1$s is not set\")",
              type.getKey());
          jw.endControlFlow();
          jw.emitStatement("fragment.%s = %s.get(\"%s\", args)", type.getName(),
              type.getBundlerFieldName(), type.getKey());
        } else {
          // not required bundler
          jw.beginControlFlow(
              "if (args.containsKey(" + JavaWriter.stringLiteral(type.getKey()) + "))");
          jw.emitStatement("throw new IllegalStateException(\"required argument %1$s is not set\")",
              type.getKey());
          jw.endControlFlow();
          jw.emitStatement("fragment.%s = %s.get(\"%s\", args)", type.getName(),
              type.getBundlerFieldName(), type.getKey());
        }
      } else {

        // Build in functions
        String op = getOperation(type);
        if (op == null) {
          throw new ProcessingException(element,
              "Can't write injector, the type is not supported by default. "
                  + "However, You can provide your own implementation by providing an %s like this: @Arg( bundler = YourBundler.class )",
              ArgsBundler.class.getSimpleName());
        }

        String cast = "Serializable".equals(op) ? "(" + type.getType() + ") " : "";
        if (!type.isRequired()) {
          jw.beginControlFlow(
              "if (args.containsKey(" + JavaWriter.stringLiteral(type.getKey()) + "))");
        } else {
          jw.beginControlFlow(
              "if (!args.containsKey(" + JavaWriter.stringLiteral(type.getKey()) + "))");
          jw.emitStatement("throw new IllegalStateException(\"required argument %1$s is not set\")",
              type.getKey());
          jw.endControlFlow();
        }

        jw.emitStatement("fragment.%1$s = %4$sargs.get%2$s(\"%3$s\")", type.getName(), op,
            type.getKey(), cast);

        if (!type.isRequired()) {
          jw.endControlFlow();
        }
      }
    }
    jw.endMethod();
  }

  private void writeBuilderMethod(String type, JavaWriter writer, ArgumentAnnotatedField arg)
      throws IOException, ProcessingException {
    writer.emitEmptyLine();
    writer.beginMethod(type, arg.getVariableName(), EnumSet.of(Modifier.PUBLIC), arg.getType(),
        arg.getVariableName());
    writePutArguments(writer, arg.getVariableName(), "mArguments", arg);
    writer.emitStatement("return this");
    writer.endMethod();
  }

  public void error(ProcessingException e) {
    String message = e.getMessage();
    if (e.getMessageArgs().length > 0) {
      message = String.format(message, e.getMessageArgs());
    }
    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, e.getElement());
  }

  public void warn(Element element, String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, message, element);
  }

  @Override public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }
}
