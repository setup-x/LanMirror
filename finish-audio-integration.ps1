$ErrorActionPreference='Stop'
$utf=[System.Text.UTF8Encoding]::new($false)
function Swap([string]$s,[string]$old,[string]$new){if([regex]::Matches($s,[regex]::Escape($old)).Count-ne 1){throw ('Anchor mismatch: '+$old)};return $s.Replace($old,$new)}
$root=$PSScriptRoot
$p=Join-Path $root 'app\src\main\java\com\lanmirror\app\RealtimeAudioPlayer.java';$s=[System.IO.File]::ReadAllText($p,[System.Text.Encoding]::UTF8)
$s=Swap $s '    private volatile Listener listener;' "    private volatile Listener listener;`n    private volatile String terminalFailure;`n    public String failureReason(){return terminalFailure;}"
$s=Swap $s '        }catch(RuntimeException e){if(!closed.get())error(source,"音频播放停止："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())+"；不会主动停止视频");}' @'
        }catch(RuntimeException e){if(!closed.get()){terminalFailure="音频播放停止："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())+"；请重新进入接收端恢复，不会主动停止视频";error(source,terminalFailure);}}
'@
[System.IO.File]::WriteAllText($p,$s,$utf)
$p=Join-Path $root 'app\src\main\java\com\lanmirror\app\MultiReceiver.java';$s=[System.IO.File]::ReadAllText($p,[System.Text.Encoding]::UTF8)
$s=Swap $s '        if(!closed.get())try{listener.onAudioSelection(selected,audioSelection.revision());}catch(RuntimeException ignored){}' @'
        if(!closed.get())try{listener.onAudioSelection(selected,audioSelection.revision());}catch(RuntimeException ignored){}
        String failure=audioPlayer.failureReason();if(selected>0&&failure!=null)playbackStatus(selected,failure,true);
'@
$s=Swap $s '    private void playbackStatus(long id,String text,boolean error){Peer peer=peers.get(id);if(peer!=null&&current(peer))try{listener.onAudioPlaybackStatus(peer.slot,id,text,error);}catch(RuntimeException ignored){}}' @'
    private void playbackStatus(long id,String text,boolean error){
        if(audioPlayer!=null&&audioPlayer.failureReason()!=null){for(Peer peer:peers.values())if(current(peer))try{listener.onAudioPlaybackStatus(peer.slot,peer.id,text,true);}catch(RuntimeException ignored){}}
        else{Peer peer=peers.get(id);if(peer!=null&&current(peer))try{listener.onAudioPlaybackStatus(peer.slot,id,text,error);}catch(RuntimeException ignored){}}
    }
'@
[System.IO.File]::WriteAllText($p,$s,$utf)
$p=Join-Path $root 'build-apk.ps1';$s=[System.IO.File]::ReadAllText($p,[System.Text.Encoding]::UTF8).Replace('1.7.0','1.8.0')
$s=Swap $s '$unsigned = Join-Path $out ''lanmirror-unsigned.apk''' @'
$generatedManifest = Join-Path $out 'standalone-manifest.xml'
$manifestText = [System.IO.File]::ReadAllText((Join-Path $root 'app\src\main\AndroidManifest.xml'),[System.Text.Encoding]::UTF8)
$manifestText = $manifestText.Replace('<manifest ', '<manifest package="com.lanmirror.app" ')
[System.IO.File]::WriteAllText($generatedManifest,$manifestText,[System.Text.UTF8Encoding]::new($false))
$unsigned = Join-Path $out 'lanmirror-unsigned.apk'
'@
$s=Swap $s "'--manifest',(Join-Path `$root 'app\src\main\AndroidManifest.xml')" "'--manifest',`$generatedManifest"
$s=Swap $s "'--target-sdk-version','37')" "'--target-sdk-version','37','--version-code','12','--version-name','1.8.0')"
[System.IO.File]::WriteAllText($p,$s,$utf)
Write-Output 'Audio terminal failures remain visible across later selections; standalone builder adapted to Gradle-owned manifest.'
