// Copyright 2000-2018 JetBrains s.r.o.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package org.jetbrains.vuejs.codeInsight

import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.JSClassExpression
import com.intellij.lang.ecmascript6.psi.JSExportAssignment
import com.intellij.lang.ecmascript6.resolve.ES6PsiUtil
import com.intellij.lang.javascript.DialectDetector
import com.intellij.lang.javascript.library.JSLibraryUtil
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.ecma6.impl.JSLocalImplicitElementImpl
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement
import com.intellij.lang.javascript.psi.stubs.impl.JSImplicitElementImpl
import com.intellij.lang.javascript.psi.util.JSStubBasedPsiTreeUtil
import com.intellij.lang.javascript.settings.JSApplicationSettings
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.html.dtd.HtmlElementDescriptorImpl
import com.intellij.psi.impl.source.html.dtd.HtmlNSDescriptorImpl
import com.intellij.psi.impl.source.xml.XmlDocumentImpl
import com.intellij.psi.impl.source.xml.XmlElementDescriptorProvider
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ArrayUtil
import com.intellij.xml.*
import com.intellij.xml.XmlElementDescriptor.CONTENT_TYPE_ANY
import icons.VuejsIcons
import org.jetbrains.vuejs.VueFileType
import org.jetbrains.vuejs.codeInsight.VueComponentDetailsProvider.Companion.getBoundName
import org.jetbrains.vuejs.codeInsight.VueComponents.Companion.getExportedDescriptor
import org.jetbrains.vuejs.codeInsight.VueComponents.Companion.isNotInLibrary
import org.jetbrains.vuejs.index.*

class VueTagProvider : XmlElementDescriptorProvider, XmlTagNameProvider {
  override fun getDescriptor(tag: XmlTag?): XmlElementDescriptor? {
    if (tag != null && hasVue(tag.project)) {
      val name = tag.name

      val localComponents = findLocalComponents(name, tag)
      if (!localComponents.isEmpty()) return multiDefinitionDescriptor(localComponents)

      val normalized = fromAsset(name)
      val globalComponents = findGlobalComponents(normalized, tag)
      if (!globalComponents.isEmpty()) return multiDefinitionDescriptor(globalComponents)

      // keep this last in case in future we would be able to normally resolve into these components
      if (VUE_FRAMEWORK_UNRESOLVABLE_COMPONENTS.contains(normalized)) {
        return VueElementDescriptor(JSImplicitElementImpl(normalized, tag))
      }
    }
    return null
  }

  private fun findLocalComponents(name: String, tag: XmlTag): List<JSImplicitElement> {
    val localComponents = mutableListOf<JSImplicitElement>()
    val decapitalized = name.decapitalize()
    processLocalComponents(tag, { foundName, element ->
      if (foundName == decapitalized || foundName == toAsset(decapitalized) || foundName == toAsset(decapitalized).capitalize()) {
        localComponents.add(element)
      }
      return@processLocalComponents true
    })
    return localComponents
  }

  private fun findGlobalComponents(normalized: String, tag: XmlTag): Collection<JSImplicitElement> {
    val resolvedVariants = resolve(normalized, GlobalSearchScope.allScope(tag.project), VueComponentsIndex.KEY)
    if (resolvedVariants != null) {
      return if (VUE_FRAMEWORK_COMPONENTS.contains(normalized)) resolvedVariants
      else {
        // if global component was defined with literal name, that's the "source of true"
        val globalExact = resolvedVariants.filter { isGlobalExact(it) }
        if (!globalExact.isEmpty()) globalExact
        else {
          // prefer library definitions of components for resolve
          // i.e. prefer the place where the name is defined, not the place where it is registered with Vue.component
          val libDef = resolvedVariants.filter { VueComponentsCache.isGlobalLibraryComponent(it) }
          if (libDef.isEmpty()) resolvedVariants.filter { isGlobal(it) }
          else libDef
        }
      }
    }
    val globalAliased = VueComponentsCache.findGlobalLibraryComponent(tag.project, normalized) ?: return emptyList()

    return setOf(globalAliased.second as? JSImplicitElement ?:
                 JSLocalImplicitElementImpl(globalAliased.first, null, globalAliased.second, null))
  }

  private fun nameVariantsWithPossiblyGlobalMark(name: String): MutableSet<String> {
    val variants = mutableSetOf(name, toAsset(name), toAsset(name).capitalize())
    variants.addAll(variants.map { it + GLOBAL_BINDING_MARK })
    return variants
  }

  private fun processLocalComponents(tag: XmlTag, processor: (String?, JSImplicitElement) -> Boolean): Boolean {
    val content = findModule(tag) ?: return true
    val defaultExport = ES6PsiUtil.findDefaultExport(content) as? JSExportAssignment ?: return true
    val component = getExportedDescriptor(defaultExport)?.obj ?: return true

    // recursive usage case
    val nameProperty = component.findProperty("name")
    val nameValue = nameProperty?.value as? JSLiteralExpression
    if (nameValue != null && nameValue.isQuotedLiteral) {
      val name = nameValue.stringValue
      if (name != null) {
        processor.invoke(name, JSImplicitElementImpl(name, nameProperty))
      }
    }

    val components = component.findProperty("components")?.objectLiteralExpressionInitializer ?: return true
    for (property in components.properties) {
      val propName = property.name ?: continue
      val meaningfulElement = JSStubBasedPsiTreeUtil.calculateMeaningfulElement(property)
      var obj = meaningfulElement as? JSObjectLiteralExpression
      var clazz: JSClassExpression<*>? = null
      if (obj == null) {
        val compDefaultExport = meaningfulElement.parent as? ES6ExportDefaultAssignment
        if (compDefaultExport != null) {
          val descriptor = VueComponents.getExportedDescriptor(compDefaultExport)
          obj = descriptor?.obj
          clazz = descriptor?.clazz
        }
      }

      if (obj != null) {
        val elements = findProperty(obj, "name")?.indexingData?.implicitElements?.filter { it.userString == VueComponentsIndex.JS_KEY }
        if (elements != null && !elements.isEmpty()) {
          if (elements.any { !processor.invoke(propName, it) }) return false
          continue
        }
        val first = obj.firstProperty
        if (first != null) {
          if (!processor.invoke(propName, JSImplicitElementImpl(propName, first))) return false
          continue
        }
      } else if (clazz != null) {
        if (!processor.invoke(propName, JSImplicitElementImpl(propName, clazz))) return false
        continue
      }
      if (!processor.invoke(propName, JSImplicitElementImpl(propName, property.nameIdentifier))) return false
    }
    return true
  }

  override fun addTagNameVariants(elements: MutableList<LookupElement>?, tag: XmlTag, prefix: String?) {
    elements ?: return
    val scriptLanguage = detectVueScriptLanguage(tag.containingFile)
    val files:MutableList<PsiFile> = mutableListOf()
    val localLookups = mutableListOf<LookupElement>()
    processLocalComponents(tag, { foundName, element ->
      addLookupVariants(localLookups, tag, scriptLanguage, element, foundName!!, false)
      files.add(element.containingFile)
      return@processLocalComponents true
    })
    elements.addAll(localLookups.map { PrioritizedLookupElement.withPriority((it as LookupElementBuilder).bold(), 100.0) })

    if (hasVue(tag.project)) {
      val namePrefix = tag.name.substringBefore(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, tag.name)
      val variants = nameVariantsWithPossiblyGlobalMark(namePrefix)
      val allComponents = VueComponentsCache.getAllComponentsGroupedByModules(tag.project, { key -> variants.any { key.contains(it, true) } }, false)
      for (entry in allComponents) {
        entry.value.keys
          .filter { !files.contains(entry.value[it]!!.first.containingFile) }
          .forEach {
            val value = entry.value[it]!!
            addLookupVariants(elements, tag, scriptLanguage, value.first, it, value.second, entry.key)
          }
      }
      elements.addAll(VUE_FRAMEWORK_COMPONENTS.map {
        LookupElementBuilder.create(it).withIcon(VuejsIcons.Vue).withTypeText("vue", true)
      })
    }
  }

  private fun addLookupVariants(elements: MutableList<LookupElement>,
                                contextTag: XmlTag,
                                scriptLanguage: String?,
                                element: PsiElement,
                                name: String,
                                isGlobal: Boolean,
                                comment: String = "") {
    if (VueFileType.INSTANCE == contextTag.containingFile.fileType) {
      // Pascal case is allowed (and recommended for 90%)
      elements.add(createVueLookup(element, toAsset(name).capitalize(), isGlobal, comment, scriptLanguage))
    }
    elements.add(createVueLookup(element, fromAsset(name), isGlobal, comment, scriptLanguage))
  }

  private fun createVueLookup(element: PsiElement,
                              name: String,
                              isGlobal: Boolean,
                              comment: String = "",
                              scriptLanguage: String?): LookupElement {
    val builder = LookupElementBuilder.create(element, name).withIcon(VuejsIcons.Vue).withTypeText(comment, true)
    if (isGlobal) {
      return builder
    }
    val settings = JSApplicationSettings.getInstance()
    if (scriptLanguage != null && "ts" == scriptLanguage ||
        DialectDetector.isTypeScript(element) && !JSLibraryUtil.isProbableLibraryFile(element.containingFile.viewProvider.virtualFile)) {
      if (settings.hasTSImportCompletionEffective(element.project)) {
        return builder.withInsertHandler(VueInsertHandler.INSTANCE)
      }
    } else {
      if (settings.isUseJavaScriptAutoImport) {
        return builder.withInsertHandler(VueInsertHandler.INSTANCE)
      }
    }
    return builder
  }

  companion object {
    private val VUE_FRAMEWORK_COMPONENTS = setOf(
      "component",
      "keep-alive",
      "slot",
      "transition",
      "transition-group"
    )
    private val VUE_FRAMEWORK_UNRESOLVABLE_COMPONENTS = setOf(
      "component",
      "slot"
    )
  }
}

fun multiDefinitionDescriptor(variants: Collection<JSImplicitElement>): VueElementDescriptor {
  assert(!variants.isEmpty())
  val sorted = variants.sortedBy { isNotInLibrary(it) }
  return VueElementDescriptor(sorted[0], sorted)
}

class VueElementDescriptor(val element: JSImplicitElement, val variants: List<JSImplicitElement> = listOf(element)) : XmlElementDescriptor {
  override fun getDeclaration() = element
  override fun getName(context: PsiElement?):String = (context as? XmlTag)?.name ?: name
  override fun getName() = fromAsset(declaration.name)
  override fun init(element: PsiElement?) {}
  override fun getQualifiedName() = name
  override fun getDefaultName() = name

  override fun getElementsDescriptors(context: XmlTag?): Array<out XmlElementDescriptor> {
    val xmlDocument = PsiTreeUtil.getParentOfType(context, XmlDocumentImpl::class.java) ?: return XmlElementDescriptor.EMPTY_ARRAY
    return xmlDocument.rootTagNSDescriptor.getRootElementsDescriptors(xmlDocument)
  }

  override fun getElementDescriptor(childTag: XmlTag?, contextTag: XmlTag?): XmlElementDescriptor? {
    val parent = contextTag?.parentTag ?: return null
    val descriptor = parent.getNSDescriptor(childTag?.namespace, true)
    return descriptor?.getElementDescriptor(childTag!!)
  }

  // it is better to use default attributes method since it is guaranteed to do not call any extension providers
  private fun getDefaultHtmlAttributes(context: XmlTag?): Array<out XmlAttributeDescriptor> =
    ((HtmlNSDescriptorImpl.guessTagForCommonAttributes(context) as? HtmlElementDescriptorImpl)?.
      getDefaultAttributeDescriptors(context) ?: emptyArray())

  override fun getAttributesDescriptors(context: XmlTag?): Array<out XmlAttributeDescriptor> {
    val result = mutableListOf<XmlAttributeDescriptor>()
    val defaultHtmlAttributes = getDefaultHtmlAttributes(context)
    result.addAll(defaultHtmlAttributes)
    VueAttributesProvider.addBindingAttributes(result, defaultHtmlAttributes)
    result.addAll(VueAttributesProvider.getDefaultVueAttributes())

    val obj = VueComponents.findComponentDescriptor(declaration)
    if (obj != null) {
      result.addAll(VueComponentDetailsProvider.INSTANCE.getAttributes(obj, true, true))
    }
    return result.toTypedArray()
  }

  override fun getAttributeDescriptor(attributeName: String?, context: XmlTag?): XmlAttributeDescriptor? {
    if (attributeName == null) return null
    if (VueAttributesProvider.DEFAULT.contains(attributeName)) return VueAttributeDescriptor(attributeName)
    val extractedName = getBoundName(attributeName)

    val obj = VueComponents.findComponentDescriptor(declaration)
    if (obj != null) {
      val descriptor = VueComponentDetailsProvider.INSTANCE.resolveAttribute(obj, extractedName ?: attributeName, true)
      if (descriptor != null) {
        return descriptor.createNameVariant(extractedName ?: attributeName)
      }
    }

    if (extractedName != null) {
      return HtmlNSDescriptorImpl.getCommonAttributeDescriptor(extractedName, context) ?: VueAttributeDescriptor(attributeName)
    }
    return HtmlNSDescriptorImpl.getCommonAttributeDescriptor(attributeName, context)
           // relax attributes check: https://vuejs.org/v2/guide/components.html#Non-Prop-Attributes
           // vue allows any non-declared as props attributes to be passed to a component
           ?: VueAttributeDescriptor(attributeName, isNonProp = true)
  }

  override fun getAttributeDescriptor(attribute: XmlAttribute?) = getAttributeDescriptor(attribute?.name, attribute?.parent)

  override fun getNSDescriptor(): XmlNSDescriptor? = null
  override fun getTopGroup(): XmlElementsGroup? = null
  override fun getContentType() = CONTENT_TYPE_ANY
  override fun getDefaultValue(): String? = null
  override fun getDependences(): Array<out Any> = ArrayUtil.EMPTY_OBJECT_ARRAY!!
}