param([string]$BaseUrl = 'http://127.0.0.1:8091')
$ErrorActionPreference = 'Stop'
function Call-Api([string]$Method,[string]$Path,$Body) {
    $argsForApi = @{ Method=$Method; Uri="$BaseUrl/api$Path"; ContentType='application/json; charset=utf-8'; TimeoutSec=105 }
    if ($null -ne $Body) { $argsForApi.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 12 -Compress)) }
    Invoke-RestMethod @argsForApi
}
function Check($Condition,[string]$Message) { if (-not $Condition) { throw $Message } }
function Check-Extraction($Document) {
    $job=@($Document.jobs)[-1]
    Check ($job.status -eq 'SUCCEEDED') ("Extraction failed: "+$job.error_code)
    Check (@($Document.proposals).Count -eq 1) 'Expected exactly one task proposal'
}
function Wait-Extraction($Document,[int]$WaitSeconds=300) {
    $deadline=[DateTime]::UtcNow.AddSeconds($WaitSeconds)
    while($true) {
        $job=@($Document.jobs)[-1]
        if($job.status -eq 'SUCCEEDED'){return $Document}
        if($job.status -eq 'FAILED'){throw ('Extraction failed after '+$job.attempt_count+' attempts: '+$job.error_code)}
        if([DateTime]::UtcNow -ge $deadline){throw ('Timed out waiting for queued document '+$Document.id)}
        Start-Sleep -Milliseconds 250
        $Document=Call-Api GET "/documents/$($Document.id)" $null
    }
}

$health=Call-Api GET '/health' $null
$live=$health.extractor -eq 'llm'
Check ($health.configured -eq $true) 'Configure the model and restart backend first'
$project=Call-Api POST '/projects' @{name="Smoke-$([Guid]::NewGuid())";timezone='Asia/Hong_Kong'}
$content=if($live){'请李明在明天前完成接口联调。'}else{'新增任务：接口联调；负责人：李明；截止：2026-11-12'}
$docBody=@{title='创建';content=$content;referenceDate='2026-11-11'}
$doc=Call-Api POST "/projects/$($project.id)/documents" $docBody
$doc=Wait-Extraction $doc
Check-Extraction $doc
Check ($doc.proposals[0].operation -eq 'CREATE_TASK') 'Expected a new task'
Check ($doc.proposals[0].candidate_json.dueDate -eq '2026-11-12') 'Reference-date interpretation failed'
$duplicate=Call-Api POST "/projects/$($project.id)/documents" $docBody
Check ($doc.id -eq $duplicate.id -and @($duplicate.jobs).Count -eq 1) 'Document dedup failed'
$review=@{title='接口联调';assigneeName='李明';dueDate='2026-11-12'}
$task=Call-Api POST "/proposals/$($doc.proposals[0].id)/approve" $review
$again=Call-Api POST "/proposals/$($doc.proposals[0].id)/approve" $review
Check ($again.id -eq $task.id -and $again.version -eq 0) 'Approval idempotency failed'
$change=if($live){"请将接口联调（任务 ID：$($task.id)）的截止日改为2026-11-15，负责人不变。"}else{"更新任务：$($task.id)；负责人：李明；截止：2026-11-15"}
$update=Call-Api POST "/projects/$($project.id)/documents" @{title='延期';content=$change;referenceDate='2026-11-11'}
$update=Wait-Extraction $update
Check-Extraction $update
Check ($update.proposals[0].operation -eq 'UPDATE_TASK' -and $update.proposals[0].target_task_id -eq $task.id) 'Expected update of captured task'
$manual=@{title='接口联调';assigneeName='李明';dueDate='2026-11-16';status='IN_PROGRESS';expectedTaskVersion=0}
Call-Api PATCH "/tasks/$($task.id)" $manual | Out-Null
$next=@{title='接口联调';assigneeName='李明';dueDate='2026-11-15';expectedTaskVersion=0}
$conflict=$false
try { Call-Api POST "/proposals/$($update.proposals[0].id)/approve" $next | Out-Null } catch {
    if ([int]$_.Exception.Response.StatusCode -eq 409) { $conflict=$true } else { throw }
}
Check $conflict 'Expected stale proposal version conflict'
$source=Call-Api GET "/documents/$($update.id)" $null
Check ($source.proposals[0].status -eq 'PENDING') 'Conflict must preserve pending proposal'
$next.expectedTaskVersion=1
$changed=Call-Api POST "/proposals/$($update.proposals[0].id)/approve" $next
$replay=Call-Api POST "/proposals/$($update.proposals[0].id)/approve" $next
Check ($changed.version -eq 2 -and $replay.version -eq 2 -and $changed.due_date -eq '2026-11-15') 'Re-review or replay failed'
$history=Call-Api GET "/tasks/$($task.id)/history" $null
$evidence=@($history | Where-Object {$_.after_json.version -eq 2})[0]
Check ($history.Count -eq 3 -and $evidence.document_id -eq $update.id) 'History evidence failed'
Write-Output ("PASS: "+$health.provider+"/"+$health.model+"; async enqueue, background extraction, relative date, dedup, review, stale 409, re-review, replay and evidence history. Project="+$project.id)
