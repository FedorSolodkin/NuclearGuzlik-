package com.guzlik.voice

/** UI owners and queued/running jobs independently keep a native model alive. */
internal class ModelLifetime(private val ready:()->Boolean, private val schedule:(()->Unit)->Unit, private val free:()->Unit) {
    private var owners=1
    private var users=0
    private var disposed=false
    private var scheduled=false
    @Synchronized fun attachOwner():Boolean {
        if(disposed || !ready())return false
        owners++
        return true
    }
    @Synchronized fun retain():Boolean {
        if(disposed || !ready())return false
        users++
        return true
    }
    @Synchronized fun releaseUse() {check(users>0);users--;scheduleIfUnused()}
    @Synchronized fun releaseOwner() {if(owners>0)owners--;scheduleIfUnused()}
    private fun scheduleIfUnused() {
        if(owners!=0 || users!=0 || disposed || scheduled)return
        scheduled=true
        schedule {
            val release=synchronized(this) {
                scheduled=false
                if(owners==0 && users==0 && !disposed){disposed=true;true}else false
            }
            if(release)free()
        }
    }
}
