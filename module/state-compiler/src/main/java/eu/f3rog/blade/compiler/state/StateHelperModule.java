package eu.f3rog.blade.compiler.state;

import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;

import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;

import blade.State;
import eu.f3rog.blade.compiler.ErrorMsg;
import eu.f3rog.blade.compiler.builder.annotation.WeaveBuilder;
import eu.f3rog.blade.compiler.builder.helper.BaseHelperModule;
import eu.f3rog.blade.compiler.builder.helper.HelperClassBuilder;
import eu.f3rog.blade.compiler.module.BundleUtils;
import eu.f3rog.blade.compiler.util.ProcessorError;
import eu.f3rog.blade.core.BundleWrapper;

import static eu.f3rog.blade.compiler.util.ProcessorUtils.addClassAsParameter;
import static eu.f3rog.blade.compiler.util.ProcessorUtils.cannotHaveAnnotation;
import static eu.f3rog.blade.compiler.util.ProcessorUtils.isActivitySubClass;
import static eu.f3rog.blade.compiler.util.ProcessorUtils.isFragmentSubClass;
import static eu.f3rog.blade.compiler.util.ProcessorUtils.isSubClassOf;

/**
 * Class {@link StateHelperModule}
 *
 * @author FrantisekGazo
 * @version 2015-12-19
 */
public class StateHelperModule
        extends BaseHelperModule {

    private static final ClassName PRESENTER_CLASS_NAME = ClassName.get("blade.mvp", "IPresenter");

    private enum HelpedClassType {
        ACTIVITY_OR_FRAGMENT, VIEW, PRESENTER, OTHER
    }

    private static final String METHOD_NAME_SAVE_SATE = "saveState";
    private static final String METHOD_NAME_RESTORE_SATE = "restoreState";

    private static final String WEAVE_onSaveInstanceState = "onSaveInstanceState";
    private static final String WEAVE_onRestoreInstanceState = "onRestoreInstanceState";
    private static final String WEAVE_onCreate = "onCreate";
    private static final String WEAVE_saveState = "saveState";
    private static final String WEAVE_restoreState = "restoreState";

    private static final String STATEFUL_ID_FORMAT = "<Stateful-%s>";

    private final List<String> mStatefulFields = new ArrayList<>();
    private HelpedClassType mHelpedClassType;

    @Override
    public void checkClass(TypeElement e) throws ProcessorError {
        // support any class
        if (isActivitySubClass(e) || isFragmentSubClass(e)) {
            mHelpedClassType = HelpedClassType.ACTIVITY_OR_FRAGMENT;
        } else if (isSubClassOf(e, View.class)) {
            mHelpedClassType = HelpedClassType.VIEW;
            if (hasViewImplementedStateMethod(e)) {
                throw new ProcessorError(e, StateErrorMsg.View_cannot_implement_state_methods);
            }
        } else if (isSubClassOf(e, PRESENTER_CLASS_NAME)) {
            mHelpedClassType = HelpedClassType.PRESENTER;
        } else {
            mHelpedClassType = HelpedClassType.OTHER;
        }
    }

    private boolean hasViewImplementedStateMethod(TypeElement viewType) {
        List<? extends Element> elements = viewType.getEnclosedElements();
        for (Element e : elements) {
            if (e.getKind() == ElementKind.METHOD) {
                String name = e.getSimpleName().toString();
                if (name.equals(WEAVE_onSaveInstanceState) || name.equals(WEAVE_onRestoreInstanceState)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void add(VariableElement e) throws ProcessorError {
        if (cannotHaveAnnotation(e)) {
            throw new ProcessorError(e, ErrorMsg.Invalid_field_with_annotation, State.class.getSimpleName());
        }

        mStatefulFields.add(e.getSimpleName().toString());
    }

    @Override
    public boolean implement(HelperClassBuilder builder) throws ProcessorError {
        if (!mStatefulFields.isEmpty()) {
            // add methods only if there is something stateful
            addSaveStateMethod(builder);
            addRestoreStateMethod(builder);
            return true;
        }
        return false;
    }

    private void addSaveStateMethod(HelperClassBuilder builder) {
        String target = "target";
        String state = "state";
        MethodSpec.Builder method = MethodSpec.methodBuilder(METHOD_NAME_SAVE_SATE)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC);

        addClassAsParameter(method, builder.getArgClassName(), target);
        method.addParameter(Bundle.class, state);

        if (mHelpedClassType != HelpedClassType.OTHER) {
            method.addAnnotation(weaveSave(builder.getClassName()));
        }

        String bundleWrapper = "bundleWrapper";
        method.beginControlFlow("if ($N == null)", state)
                .addStatement("throw new $T($S)", IllegalArgumentException.class, "State cannot be null!")
                .endControlFlow()
                .addStatement("$T $N = $T.from($N)", BundleWrapper.class, bundleWrapper, BundleWrapper.class, state);

        BundleUtils.putToBundle(method, target, mStatefulFields, STATEFUL_ID_FORMAT, bundleWrapper);

        builder.getBuilder().addMethod(method.build());
    }

    private void addRestoreStateMethod(HelperClassBuilder builder) {
        String target = "target";
        String state = "state";
        MethodSpec.Builder method = MethodSpec.methodBuilder(METHOD_NAME_RESTORE_SATE)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC);

        addClassAsParameter(method, builder.getArgClassName(), target);
        method.addParameter(Bundle.class, state);

        if (mHelpedClassType != HelpedClassType.OTHER) {
            method.addAnnotation(weaveRestore(builder.getClassName()));
        }

        String bundleWrapper = "bundleWrapper";
        method.beginControlFlow("if ($N == null)", state)
                .addStatement("return")
                .endControlFlow()
                .addStatement("$T $N = $T.from($N)", BundleWrapper.class, bundleWrapper, BundleWrapper.class, state);

        BundleUtils.getFromBundle(method, target, mStatefulFields, STATEFUL_ID_FORMAT, bundleWrapper);

        builder.getBuilder().addMethod(method.build());
    }

    private AnnotationSpec weaveSave(ClassName helperName) {
        switch (mHelpedClassType) {
            case ACTIVITY_OR_FRAGMENT:
                return WeaveBuilder.weave().method(WEAVE_onSaveInstanceState, Bundle.class)
                        .withStatement("%s.%s(this, $1);", helperName, METHOD_NAME_SAVE_SATE)
                        .build();

            case PRESENTER:
                return WeaveBuilder.weave().method(WEAVE_saveState, Object.class)
                        .withStatement("%s.%s(this, (%s) $1);", helperName, METHOD_NAME_SAVE_SATE, Bundle.class.getCanonicalName())
                        .build();
            case VIEW:
                return WeaveBuilder.weave().method(WEAVE_onSaveInstanceState)
                        .withStatement("%s bundle = new %s();", Bundle.class.getName(), Bundle.class.getName())
                        .withStatement("bundle.putParcelable('PARENT_STATE', super.onSaveInstanceState());")
                        .withStatement("%s.%s(this, bundle);", helperName, METHOD_NAME_SAVE_SATE)
                        .withStatement("return bundle;")
                        .build();
            default:
                throw new IllegalStateException();
        }
    }

    private AnnotationSpec weaveRestore(ClassName helperName) {
        switch (mHelpedClassType) {
            case ACTIVITY_OR_FRAGMENT:
                return WeaveBuilder.weave().method(WEAVE_onCreate, Bundle.class)
                        .withStatement("%s.%s(this, $1);", helperName, METHOD_NAME_RESTORE_SATE)
                        .build();

            case PRESENTER:
                return WeaveBuilder.weave().method(WEAVE_restoreState, Object.class)
                        .withStatement("%s.%s(this, (%s) $1);", helperName, METHOD_NAME_RESTORE_SATE, Bundle.class.getCanonicalName())
                        .build();
            case VIEW:
                return WeaveBuilder.weave().method(WEAVE_onRestoreInstanceState, Parcelable.class)
                        .withStatement("if ($1 instanceof %s) {", Bundle.class.getName())
                        .withStatement("%s bundle = (%s) $1;", Bundle.class.getName(), Bundle.class.getName())
                        .withStatement("%s.%s(this, bundle);", helperName, METHOD_NAME_RESTORE_SATE)
                        .withStatement("super.onRestoreInstanceState(bundle.getParcelable('PARENT_STATE'));")
                        .withStatement("} else {")
                        .withStatement("super.onRestoreInstanceState($1);")
                        .withStatement("}")
                        .withStatement("return;")
                        .build();
            default:
                throw new IllegalStateException();
        }
    }

}
