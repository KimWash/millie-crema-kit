#!/usr/bin/env python3
"""Append API 18/19 backport members to framework smali (idempotent; marker comment)."""
import os, sys
ROOT = sys.argv[1]
MARK = '# --- api19 backport (millie-crema) ---'
P = {}
P['android/graphics/drawable/Drawable.smali'] = r'''
.field private mAutoMirroredCompat:Z

.method public setAutoMirrored(Z)V
    .registers 2
    iput-boolean p1, p0, Landroid/graphics/drawable/Drawable;->mAutoMirroredCompat:Z
    return-void
.end method

.method public isAutoMirrored()Z
    .registers 2
    iget-boolean v0, p0, Landroid/graphics/drawable/Drawable;->mAutoMirroredCompat:Z
    return v0
.end method

.method public getAlpha()I
    .registers 2
    const/16 v0, 0xff
    return v0
.end method
'''
P['android/graphics/drawable/DrawableContainer$DrawableContainerState.smali'] = r'''
.method public final getChild(I)Landroid/graphics/drawable/Drawable;
    .registers 3
    if-ltz p1, :cond_null
    iget v0, p0, Landroid/graphics/drawable/DrawableContainer$DrawableContainerState;->mNumChildren:I
    if-ge p1, v0, :cond_null
    iget-object v0, p0, Landroid/graphics/drawable/DrawableContainer$DrawableContainerState;->mDrawables:[Landroid/graphics/drawable/Drawable;
    aget-object v0, v0, p1
    return-object v0
    :cond_null
    const/4 v0, 0x0
    return-object v0
.end method
'''
P['android/graphics/drawable/InsetDrawable.smali'] = r'''
.method public getDrawable()Landroid/graphics/drawable/Drawable;
    .registers 2
    iget-object v0, p0, Landroid/graphics/drawable/InsetDrawable;->mInsetState:Landroid/graphics/drawable/InsetDrawable$InsetState;
    iget-object v0, v0, Landroid/graphics/drawable/InsetDrawable$InsetState;->mDrawable:Landroid/graphics/drawable/Drawable;
    return-object v0
.end method
'''
P['android/animation/ObjectAnimator.smali'] = r'''
.method public setAutoCancel(Z)V
    .registers 2
    return-void
.end method
'''
P['android/view/View.smali'] = r'''
.method public isInLayout()Z
    .registers 2
    const/4 v0, 0x0
    return v0
.end method

.method public getAccessibilityLiveRegion()I
    .registers 2
    const/4 v0, 0x0
    return v0
.end method

.method public setAccessibilityLiveRegion(I)V
    .registers 2
    return-void
.end method
'''
P['android/view/accessibility/AccessibilityEvent.smali'] = r'''
.method public getContentChangeTypes()I
    .registers 2
    const/4 v0, 0x0
    return v0
.end method

.method public setContentChangeTypes(I)V
    .registers 2
    return-void
.end method
'''
P['android/view/ScaleGestureDetector.smali'] = r'''
.method public isQuickScaleEnabled()Z
    .registers 2
    const/4 v0, 0x0
    return v0
.end method

.method public setQuickScaleEnabled(Z)V
    .registers 2
    return-void
.end method
'''
P['android/widget/PopupWindow.smali'] = r'''
.method public showAsDropDown(Landroid/view/View;III)V
    .registers 5
    invoke-virtual {p0, p1, p2, p3}, Landroid/widget/PopupWindow;->showAsDropDown(Landroid/view/View;II)V
    return-void
.end method
'''
P['android/widget/AbsListView.smali'] = r'''
.method public canScrollList(I)Z
    .registers 3
    invoke-virtual {p0, p1}, Landroid/widget/AbsListView;->canScrollVertically(I)Z
    move-result v0
    return v0
.end method

.method public scrollListBy(I)V
    .registers 3
    neg-int v0, p1
    invoke-virtual {p0, v0, v0}, Landroid/widget/AbsListView;->trackMotionScroll(II)Z
    return-void
.end method
'''
P['android/graphics/Bitmap.smali'] = r'''
.method public final setPremultiplied(Z)V
    .registers 2
    return-void
.end method
'''
P['android/content/Context.smali'] = r'''
.method public getExternalFilesDirs(Ljava/lang/String;)[Ljava/io/File;
    .registers 5
    const/4 v0, 0x1
    new-array v0, v0, [Ljava/io/File;
    invoke-virtual {p0, p1}, Landroid/content/Context;->getExternalFilesDir(Ljava/lang/String;)Ljava/io/File;
    move-result-object v1
    const/4 v2, 0x0
    aput-object v1, v0, v2
    return-object v0
.end method

.method public getExternalCacheDirs()[Ljava/io/File;
    .registers 4
    const/4 v0, 0x1
    new-array v0, v0, [Ljava/io/File;
    invoke-virtual {p0}, Landroid/content/Context;->getExternalCacheDir()Ljava/io/File;
    move-result-object v1
    const/4 v2, 0x0
    aput-object v1, v0, v2
    return-object v0
.end method

.method public getObbDirs()[Ljava/io/File;
    .registers 4
    const/4 v0, 0x1
    new-array v0, v0, [Ljava/io/File;
    invoke-virtual {p0}, Landroid/content/Context;->getObbDir()Ljava/io/File;
    move-result-object v1
    const/4 v2, 0x0
    aput-object v1, v0, v2
    return-object v0
.end method
'''
P['android/os/Environment.smali'] = r'''
.method public static getStorageState(Ljava/io/File;)Ljava/lang/String;
    .registers 2
    invoke-static {}, Landroid/os/Environment;->getExternalStorageState()Ljava/lang/String;
    move-result-object v0
    return-object v0
.end method
'''
P['android/app/AlarmManager.smali'] = r'''
.method public setExact(IJLandroid/app/PendingIntent;)V
    .registers 5
    invoke-virtual {p0, p1, p2, p3, p4}, Landroid/app/AlarmManager;->set(IJLandroid/app/PendingIntent;)V
    return-void
.end method
'''
P['android/content/ContentResolver.smali'] = r'''
.method public final openFileDescriptor(Landroid/net/Uri;Ljava/lang/String;Landroid/os/CancellationSignal;)Landroid/os/ParcelFileDescriptor;
    .registers 5
    invoke-virtual {p0, p1, p2}, Landroid/content/ContentResolver;->openFileDescriptor(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;
    move-result-object v0
    return-object v0
.end method
'''
P['android/content/pm/PackageManager.smali'] = r'''
.method public queryIntentContentProviders(Landroid/content/Intent;I)Ljava/util/List;
    .registers 4
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
    return-object v0
.end method
'''
for rel, body in P.items():
    path = os.path.join(ROOT, rel)
    s = open(path).read()
    if MARK in s:
        print('skip (already)', rel); continue
    open(path, 'a').write('\n' + MARK + '\n' + body)
    print('patched', rel)
