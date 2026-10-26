import { useEffect, useState } from 'react'
import TeamSettingsPanel from './TeamSettingsPanel'
import WorkspaceArchivePanel from './WorkspaceArchivePanel'

type Props = { workspaceId: string | null; workspaceNames: string[]; disabled: boolean;
  onBusyChange: (busy: boolean) => void; onApplied: () => Promise<void> }

export default function WorkspaceTransferSettings(props: Props) {
  const [settingsBusy, setSettingsBusy] = useState(false)
  const [archiveBusy, setArchiveBusy] = useState(false)
  useEffect(() => {
    props.onBusyChange(settingsBusy || archiveBusy)
    return () => props.onBusyChange(false)
  }, [settingsBusy, archiveBusy, props.onBusyChange])
  return <>
    <TeamSettingsPanel {...props} disabled={props.disabled || archiveBusy} onBusyChange={setSettingsBusy} />
    <WorkspaceArchivePanel {...props} disabled={props.disabled || settingsBusy} onBusyChange={setArchiveBusy} />
  </>
}
