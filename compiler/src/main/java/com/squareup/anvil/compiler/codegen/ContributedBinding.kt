package com.squareup.anvil.compiler.codegen

import com.squareup.anvil.annotations.ContributesBinding.Priority
import com.squareup.anvil.compiler.anyFqName
import com.squareup.anvil.compiler.api.AnvilCompilationException
import com.squareup.anvil.compiler.internal.reference.AnnotationReference
import com.squareup.anvil.compiler.internal.reference.ClassReference
import com.squareup.anvil.compiler.internal.reference.allSuperTypeClassReferences
import com.squareup.anvil.compiler.internal.reference.toClassReference
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.TypeName
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import kotlin.LazyThreadSafetyMode.NONE

internal data class ContributedBinding(
  val contributedClass: ClassReference,
  val mapKeys: List<AnnotationSpec>,
  val qualifiers: List<AnnotationSpec>,
  val boundType: ClassReference,
  val boundTypeParameters: List<TypeName>,
  val priority: Priority,
  val qualifiersKeyLazy: Lazy<String>
)

internal fun AnnotationReference.toContributedBinding(
  isMultibinding: Boolean,
  module: ModuleDescriptor,
): ContributedBinding {
  val (boundType, boundTypeParameters) = requireBoundType(module)

  val mapKeys = if (isMultibinding) {
    declaringClass().annotations.filter { it.isMapKey() }.map { it.toAnnotationSpec() }
  } else {
    emptyList()
  }

  val ignoreQualifier = ignoreQualifier()
  val qualifiers = if (ignoreQualifier) {
    emptyList()
  } else {
    declaringClass().annotations.filter { it.isQualifier() }.map { it.toAnnotationSpec() }
  }

  println("create CB $boundTypeParameters")

  return ContributedBinding(
    contributedClass = declaringClass(),
    mapKeys = mapKeys,
    qualifiers = qualifiers,
    boundType = boundType,
    boundTypeParameters = boundTypeParameters,
    priority = priority(),
    qualifiersKeyLazy = declaringClass().qualifiersKeyLazy(boundType, ignoreQualifier)
  ).also { println("got CB $it") }
}

private fun AnnotationReference.requireBoundType(module: ModuleDescriptor): Pair<ClassReference, List<TypeName>> {
  val boundFromAnnotation = boundTypeOrNull()

  if (boundFromAnnotation != null) {
    // Since all classes extend Any, we can stop here.
    if (boundFromAnnotation.fqName == anyFqName) return anyFqName.toClassReference(module) to emptyList()

    val directBoundType = declaringClass().directSuperTypeReferences().firstOrNull {
      it.asClassReferenceOrNull()?.fqName == boundFromAnnotation.fqName
    }
    println("DBT $directBoundType")

    if (directBoundType != null) {
      val typeArguments = (directBoundType.asTypeNameOrNull() as? ParameterizedTypeName)?.typeArguments ?: emptyList()
      println("TA ${directBoundType.asTypeNameOrNull()?.javaClass} $typeArguments")
      return directBoundType.asClassReference() to typeArguments
    }

    // ensure that the bound type is actually a supertype of the contributing class
    val boundType = declaringClass().allSuperTypeClassReferences()
      .firstOrNull {
        it.fqName == boundFromAnnotation.fqName
      }
      ?: throw AnvilCompilationException(
        "$fqName contributes a binding for ${boundFromAnnotation.fqName}, " +
          "but doesn't extend this type."
      )
    return boundType to emptyList()
  }

  // If there's no bound type in the annotation,
  // it must be the only supertype of the contributing class
  val boundType = declaringClass().directSuperTypeReferences().singleOrNull()
    ?: throw AnvilCompilationException(
      message = "$fqName contributes a binding, but does not " +
        "specify the bound type. This is only allowed with exactly one direct super type. " +
        "If there are multiple or none, then the bound type must be explicitly defined in " +
        "the @$shortName annotation."
    )

  val typeArguments = (boundType.asTypeNameOrNull() as? ParameterizedTypeName)?.typeArguments ?: emptyList()
  println("TA ${boundType.asTypeNameOrNull()?.javaClass} $typeArguments")
  return boundType.asClassReference() to typeArguments
}

private fun ClassReference.qualifiersKeyLazy(
  boundType: ClassReference,
  ignoreQualifier: Boolean
): Lazy<String> {
  // Careful! If we ever decide to support generic types, then we might need to use the
  // Kotlin type and not just the FqName.
  if (ignoreQualifier) {
    return lazy { boundType.fqName.asString() }
  }

  return lazy(NONE) { boundType.fqName.asString() + qualifiersKey() }
}

private fun ClassReference.qualifiersKey(): String {
  return annotations
    .filter { it.isQualifier() }
    // Note that we sort all elements. That's important for a stable string comparison.
    .sortedBy { it.classReference }
    .joinToString(separator = "") { annotation ->
      annotation.fqName.asString() +
        annotation.arguments.joinToString(separator = "") { argument ->
          val valueString = when (val value = argument.value<Any>()) {
            is ClassReference -> value.fqName.asString()
            else -> value.toString()
          }

          argument.resolvedName + valueString
        }
    }
}
