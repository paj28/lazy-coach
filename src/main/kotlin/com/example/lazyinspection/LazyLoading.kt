package com.example.lazyinspection

import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.search.PsiShortNamesCache
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.core.replaced
import org.jetbrains.kotlin.idea.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.jvm.KotlinJavaPsiFacade


val lazyOperators = setOf("referencedOn", "optionalReferencedOn", "referrersOn", "optionalReferrersOn",
        "backReferencedOn", "optionalBackReferencedOn", "via")
val queryFunctions = setOf("find", "all", "findById")
val eagerFunctions = setOf("load", "with")

class LazyLoadingInspection : AbstractKotlinInspection() {
    override fun getDisplayName(): String {
        return "Lazy loading"
    }

    override fun getGroupDisplayName(): String {
        return "Exposed"
    }

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object: KtVisitorVoid() {
            val entityClasses = mutableSetOf<String>()
            val lazyFields = mutableSetOf<String>()
            var queryExpression: KtExpression? = null
            val withClauses = mutableSetOf<String>()

            override fun visitClass(klass: KtClass) {
                for (superType in klass.superTypeListEntries) {
                    if (superType is KtSuperTypeCallEntry) {
                        // TODO: avoid using text
                        if (superType.calleeExpression.text == "IntEntity") {
                            klass.getClassId()?.apply { entityClasses.add(this.asString()) }
                        }
                    }
                }
            }

            override fun visitPropertyDelegate(delegate: KtPropertyDelegate) {
                val expression = delegate.expression
                if (expression is KtBinaryExpression) {
                    if (expression.operationReference.getReferencedName() in lazyOperators) {
                        val klass = delegate.parent.parent.parent as KtClass
                        val fieldName = (delegate.parent as KtProperty).name
                        val lazyField = "${klass.getClassId()}::$fieldName"
//                        println("LAZY: $lazyField")
                        lazyFields.add(lazyField)
                    }
                }
            }

            override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression) {
                val bindingContext = expression.receiverExpression.analyze()
                val type = bindingContext.getType(expression.receiverExpression)
                val selectorExpression = expression.selectorExpression
                if (type != null && selectorExpression is KtNameReferenceExpression) {
                    val desc = "${type.constructor.declarationDescriptor.classId}::${selectorExpression.getReferencedName()}"
//                    println("LOAD: $desc")
                    if (desc in lazyFields && desc !in withClauses) {
                        holder.registerProblem(expression, "Relationship will be lazy loaded")
                        queryExpression?.apply {
                            holder.registerProblem(
                                this,
                                "Query could benefit from eager loading",
                                EagerLoadQuickFix(desc.substring(desc.lastIndexOf("/") + 1))
                            )
                        }
                    }
                }
                if (selectorExpression is KtCallExpression) {
                    val calleeExpression = selectorExpression.calleeExpression
                    if (type != null && calleeExpression is KtNameReferenceExpression) {
                        if (calleeExpression.getReferencedName() in queryFunctions) {
                            handleQuery(expression.receiverExpression)
                        }
                        if (calleeExpression.getReferencedName() in eagerFunctions) {
                            // TODO: type check SizedIterable<>
                            for (argument in selectorExpression.valueArgumentList?.arguments ?: emptyList()) {
                                val argumentExpression = argument.getArgumentExpression()
                                if (argumentExpression is KtCallableReferenceExpression) {
                                    val lhs = argumentExpression.lhs
                                    if (lhs is KtNameReferenceExpression) {
                                        val rhs = argumentExpression.callableReference.getReferencedName()
                                        // TODO: use the PSI type system
                                        val entityClass = entityClasses.find { it.endsWith("/${lhs.getReferencedName()}") }
                                        val withClause = "$entityClass::$rhs"
//                                        println("WITH: $withClause")
                                        withClauses.add(withClause)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                expression.arrayExpression?.apply { handleQuery(this) }
            }

            fun handleQuery(expression: KtExpression) {
                val bindingContext = expression.analyze()
                val type = bindingContext.getType(expression)
                if (type != null) {
                    // TODO: do this properly
                    val clsId = (type.constructor.declarationDescriptor.classId?.asString() ?: "").replace(".Companion", "")
                    if (clsId in entityClasses) {
                        // TODO: cope with more than one query
                        queryExpression = expression.parent as KtExpression
                        withClauses.clear()
                    }
                }

            }
        }
    }
}

class LazyLoadingProvider : InspectionToolProvider {
    override fun getInspectionClasses(): Array<Class<out LocalInspectionTool>> {
        return arrayOf(LazyLoadingInspection::class.java)
    }
}


class EagerLoadQuickFix(private val relation: String): LocalQuickFix {
    override fun getFamilyName(): String {
        return name
    }

    override fun getName(): String {
        return "Eager load"
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val element = descriptor.psiElement
        var keyword = "load"
        if (element is KtExpression) {
            val bindingContext = element.analyze()
            val type = bindingContext.getType(element)
            if (type?.constructor?.declarationDescriptor?.classId?.asString() == "org/jetbrains/exposed/sql/SizedIterable") {
                keyword = "with"
            }
        }
        val psiFactory = KtPsiFactory(project)
        // TODO: select load/with appropriately
        val newElement = psiFactory.createExpressionByPattern(element.text + ".$keyword($relation)")
        element.replaced(newElement)
    }
}
