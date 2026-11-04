import { MultiSelect, Stack, Switch, Text } from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import type { ServiceTypeRule } from './resourcesApi';
import type { ResourceAssignment, StaffAssignment, StaffRole } from './scheduleApi';
import type { ScheduleLookups } from './useScheduleLookups';

type AssignmentFieldsProps = {
  /** The event's service type; staff without its qualification and rooms that do not allow it are marked. */
  serviceTypeId: string | null;
  staff: StaffAssignment[];
  onStaffChange: (staff: StaffAssignment[]) => void;
  resources: ResourceAssignment[];
  onResourcesChange: (resources: ResourceAssignment[]) => void;
  lookups: ScheduleLookups;
  /** Service types allowed in each room; a room without loaded rules is not restricted here. */
  roomRules: ReadonlyMap<string, ServiceTypeRule[]>;
  /** Assignments of a block: only rooms, always taken whole. */
  block?: boolean;
};

/**
 * Pickers for the people who run an event and the rooms it takes place in. Each room can be
 * taken whole or shared with other events that do not block it.
 */
export function AssignmentFields({
  serviceTypeId,
  staff,
  onStaffChange,
  resources,
  onResourcesChange,
  lookups,
  roomRules,
  block = false,
}: AssignmentFieldsProps) {
  const texts = pl.schedule.assignmentFields;
  const leadIds = staff.filter((member) => member.role === 'LEAD').map((member) => member.staffId);
  const assistantIds = staff
    .filter((member) => member.role === 'ASSISTANT')
    .map((member) => member.staffId);
  const selectedIds = new Set(resources.map((resource) => resource.resourceId));
  const roomIds = resources.map((resource) => resource.resourceId);

  function staffOptions(excluded: string[]) {
    const selected = new Set(staff.map((member) => member.staffId));
    return lookups.staff
      .filter((member) => !excluded.includes(member.id))
      .filter((member) => member.isActive || selected.has(member.id))
      .map((member) => {
        const notes: string[] = [];
        if (!member.isActive) {
          notes.push(texts.inactive);
        }
        if (serviceTypeId && !member.serviceTypeIds.includes(serviceTypeId)) {
          notes.push(texts.notQualified);
        }
        return {
          value: member.id,
          label:
            notes.length > 0 ? `${member.displayName} (${notes.join(', ')})` : member.displayName,
        };
      });
  }

  function setRole(ids: string[], role: StaffRole) {
    const others = staff.filter((member) => member.role !== role);
    onStaffChange([...ids.map((staffId) => ({ staffId, role })), ...others]);
  }

  function roomAllowed(roomId: string): boolean {
    const rules = roomRules.get(roomId);
    return !serviceTypeId || !rules || rules.some((rule) => rule.serviceTypeId === serviceTypeId);
  }

  const roomOptions = lookups.rooms
    .filter((room) => room.isActive || selectedIds.has(room.id))
    .map((room) => {
      const allowed = roomAllowed(room.id);
      return {
        value: room.id,
        label: allowed ? room.name : `${room.name} (${texts.roomNotAllowed})`,
        disabled: !allowed && !selectedIds.has(room.id),
      };
    });
  function setRooms(ids: string[]) {
    const previous = new Map(resources.map((resource) => [resource.resourceId, resource]));
    onResourcesChange(
      ids.map((resourceId) => previous.get(resourceId) ?? { resourceId, blocksWholeRoom: true }),
    );
  }

  function setBlocks(resourceId: string, blocksWholeRoom: boolean) {
    onResourcesChange(
      resources.map((resource) =>
        resource.resourceId === resourceId ? { ...resource, blocksWholeRoom } : resource,
      ),
    );
  }

  return (
    <Stack gap="sm">
      {!block && (
        <>
          <MultiSelect
            label={texts.leads}
            placeholder={texts.staffPlaceholder}
            nothingFoundMessage={texts.nothingFound}
            searchable
            data={staffOptions(assistantIds)}
            value={leadIds}
            onChange={(ids) => setRole(ids, 'LEAD')}
          />
          <MultiSelect
            label={texts.assistants}
            placeholder={texts.staffPlaceholder}
            nothingFoundMessage={texts.nothingFound}
            searchable
            data={staffOptions(leadIds)}
            value={assistantIds}
            onChange={(ids) => setRole(ids, 'ASSISTANT')}
          />
        </>
      )}
      <MultiSelect
        label={texts.rooms}
        placeholder={texts.roomsPlaceholder}
        nothingFoundMessage={texts.nothingFound}
        searchable
        data={roomOptions}
        value={roomIds}
        onChange={setRooms}
      />
      {roomIds.length > 0 && !block && (
        <Stack gap={4}>
          {roomIds.map((roomId) => (
            <Switch
              key={roomId}
              label={texts.blocksWholeRoom(lookups.resourceById.get(roomId)?.name ?? roomId)}
              checked={
                resources.find((resource) => resource.resourceId === roomId)?.blocksWholeRoom ??
                true
              }
              onChange={(event) => setBlocks(roomId, event.currentTarget.checked)}
            />
          ))}
          <Text size="xs" c="dimmed">
            {texts.blocksWholeRoomDescription}
          </Text>
        </Stack>
      )}
    </Stack>
  );
}
