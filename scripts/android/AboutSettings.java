package com.jedflix.tv;

import android.content.Context;
import androidx.compose.foundation.lazy.*;
import androidx.compose.runtime.*;
import androidx.compose.runtime.internal.ComposableLambdaKt;
import androidx.compose.ui.platform.AndroidCompositionLocals_androidKt;
import com.stremio.tv.views.settings.composables.*;
import com.stremio.tv.views.settings.composables.rows.ButtonRowKt;
import kotlin.Unit;
import kotlin.jvm.functions.*;

/** Uses the fork's existing Compose section layout and TV focus behavior. */
public final class AboutSettings {
    private AboutSettings() {}
    public static void Content(Composer c) {
        c.startReplaceGroup(0x4a555001);
        Object remembered=c.rememberedValue();
        if (remembered==Composer.Companion.getEmpty()) {
            remembered=new Form(c.consume(AndroidCompositionLocals_androidKt.getLocalContext()));
            c.updateRememberedValue(remembered);
        }
        SectionListKt.SectionList(((Form)remembered).sections,c,0);
        c.endReplaceGroup();
    }
    @SuppressWarnings("unchecked")
    private static Function2<Composer,Integer,Unit> content(int key,Function2<Composer,Integer,Unit> block) {
        return (Function2<Composer,Integer,Unit>)ComposableLambdaKt.composableLambdaInstance(key,true,block);
    }
    private static final class Form implements RememberObserver {
        final Context context;
        final JedflixUpdates updates;
        final MutableState<Integer> revision=SnapshotStateKt.mutableStateOf(0,SnapshotStateKt.<Integer>structuralEqualityPolicy());
        final Runnable observer=()->revision.setValue(revision.getValue()+1);
        final Function2<Composer,Integer,Unit> empty=content(0x4a555002,(c,f)->Unit.INSTANCE);
        final Function1<LazyListScope,Unit> sections;
        Form(Context context) {
            this.context=context; updates=JedflixUpdates.get(context);
            Function2<Composer,Integer,Unit> body=content(0x4a555003,(c,f)->{
                revision.getValue();
                SectionItemKt.SectionItem("Jedflix "+JedflixBuild.VERSION_NAME+" ("+JedflixBuild.VERSION_CODE+")",null,empty,c,0,2);
                SectionItemKt.SectionItem("A Jedflix fork of Stremio for Android TV",null,empty,c,0,2);
                SectionItemKt.SectionItem("Based on Stremio Android TV 1.10.4. Stremio and its contributors provide the underlying player and addon platform.",null,empty,c,0,2);
                SectionItemKt.SectionItem(updates.status,null,empty,c,0,2);
                ButtonRowKt.ButtonRow("Software update",updates.actionLabel(),null,
                    ()->{ updates.action(); return Unit.INSTANCE; },c,0,4);
                SectionItemKt.SectionItem("github.com/"+JedflixBuild.REPOSITORY,null,empty,c,0,2);
                return Unit.INSTANCE;
            });
            Function3<LazyItemScope,Composer,Integer,Unit> item=(scope,c,f)->{ SectionKt.Section("About Jedflix",body,c,0,0); return Unit.INSTANCE; };
            @SuppressWarnings("unchecked") Function3<LazyItemScope,Composer,Integer,Unit> block=
                (Function3<LazyItemScope,Composer,Integer,Unit>)ComposableLambdaKt.composableLambdaInstance(0x4a555004,true,item);
            sections=scope->{ scope.item("jedflix-about","section",block); return Unit.INSTANCE; };
        }
        @Override public void onRemembered() { updates.listen(observer); }
        @Override public void onForgotten() { updates.unlisten(observer); }
        @Override public void onAbandoned() { onForgotten(); }
    }
}
