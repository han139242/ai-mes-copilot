package com.neuedu.ems.service.impl.sys;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.sys.SysRoleMenu;
import com.neuedu.ems.entity.sys.SysRole;
import com.neuedu.ems.entity.sys.SysMenu;
import com.neuedu.ems.mapper.sys.SysMenuMapper;
import com.neuedu.ems.mapper.sys.SysRoleMapper;
import com.neuedu.ems.mapper.sys.SysRoleMenuMapper;
import com.neuedu.ems.service.sys.PermissionService;
import com.neuedu.ems.service.sys.SysRoleMenuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 角色菜单关联 Service 实现类。
 */
@Service
public class SysRoleMenuServiceImpl extends ServiceImpl<SysRoleMenuMapper, SysRoleMenu> implements SysRoleMenuService {

    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Autowired
    private SysMenuMapper sysMenuMapper;

    @Autowired
    private PermissionService permissionService;

    @Override
    public Page<SysRoleMenu> pageRoleMenus(Integer current, Integer size, Long roleId, Long menuId) {
        Page<SysRoleMenu> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildRoleMenuQuery(roleId, menuId));
    }

    @Override
    public SysRoleMenu getRoleMenuById(Long id) {
        QueryWrapper<SysRoleMenu> wrapper = new QueryWrapper<>();
        wrapper.eq("id", id);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public boolean saveRoleMenu(SysRoleMenu roleMenu) {
        if (isSuperAdminRole(roleMenu.getRoleId())) {
            return false;
        }
        if (roleMenu.getCreatedTime() == null) {
            roleMenu.setCreatedTime(LocalDateTime.now());
        }
        if (roleMenu.getDeletedFlag() == null) {
            roleMenu.setDeletedFlag(0);
        }
        boolean saved = baseMapper.insert(roleMenu) > 0;
        if (saved) {
            permissionService.evictAll();
        }
        return saved;
    }

    @Override
    public boolean updateRoleMenu(SysRoleMenu roleMenu) {
        if (roleMenu.getId() == null) {
            return false;
        }
        SysRoleMenu existing = getRoleMenuById(roleMenu.getId());
        if (existing == null || isSuperAdminRole(existing.getRoleId()) || isSuperAdminRole(roleMenu.getRoleId())) {
            return false;
        }
        roleMenu.setDeletedFlag(null);
        boolean updated = baseMapper.updateById(roleMenu) > 0;
        if (updated) {
            permissionService.evictAll();
        }
        return updated;
    }

    @Override
    public boolean removeRoleMenuById(Long id) {
        SysRoleMenu existing = getRoleMenuById(id);
        if (existing == null || isSuperAdminRole(existing.getRoleId())) {
            return false;
        }
        SysRoleMenu roleMenu = new SysRoleMenu();
        roleMenu.setId(id);
        roleMenu.setDeletedFlag(1);
        boolean removed = baseMapper.updateById(roleMenu) > 0;
        if (removed) {
            permissionService.evictAll();
        }
        return removed;
    }

    @Override
    public List<SysRoleMenu> listByRoleId(Long roleId) {
        QueryWrapper<SysRoleMenu> wrapper = new QueryWrapper<>();
        wrapper.eq("role_id", roleId);
        wrapper.eq("deleted_flag", 0);
        wrapper.orderByAsc("menu_id").orderByDesc("id");
        return baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean replaceRoleMenus(Long roleId, List<Long> menuIds) {
        if (!isAssignableRole(roleId)) {
            return false;
        }
        Set<Long> targetMenuIds = normalizeMenuIds(menuIds);
        if (!allMenusExist(targetMenuIds)) {
            return false;
        }
        Map<Long, SysRoleMenu> existingByMenu = loadRelationsByMenu(roleId);
        LocalDateTime now = LocalDateTime.now();
        refreshExistingRelations(existingByMenu, targetMenuIds, now);
        insertNewRelations(roleId, targetMenuIds, now);
        permissionService.evictAll();
        return true;
    }

    /**
     * 校验角色存在、已启用且不是超管角色，超管权限不允许在页面侧改写。
     *
     * @param roleId 角色主键
     * @return 是否允许改写权限
     */
    private boolean isAssignableRole(Long roleId) {
        if (roleId == null) {
            return false;
        }
        QueryWrapper<SysRole> roleQuery = new QueryWrapper<>();
        roleQuery.eq("role_id", roleId).eq("deleted_flag", 0).eq("status", "ENABLE");
        SysRole role = sysRoleMapper.selectOne(roleQuery);
        return role != null && !"SUPER_ADMIN".equals(role.getRoleCode());
    }

    /**
     * 规整目标菜单主键集合，去掉空值并保序去重。
     *
     * @param menuIds 原始菜单主键
     * @return 去重后的菜单主键集合
     */
    private Set<Long> normalizeMenuIds(List<Long> menuIds) {
        if (menuIds == null) {
            return new LinkedHashSet<>();
        }
        return menuIds.stream().filter(id -> id != null).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 校验目标菜单全部存在且未删除。
     *
     * @param targetMenuIds 目标菜单主键
     * @return 是否全部可用
     */
    private boolean allMenusExist(Set<Long> targetMenuIds) {
        if (targetMenuIds.isEmpty()) {
            return true;
        }
        QueryWrapper<SysMenu> menuQuery = new QueryWrapper<>();
        menuQuery.in("menu_id", targetMenuIds).eq("deleted_flag", 0);
        return sysMenuMapper.selectCount(menuQuery) == targetMenuIds.size();
    }

    /**
     * 读取角色已有的菜单关系，按菜单主键索引。
     *
     * @param roleId 角色主键
     * @return 已存在的关系
     */
    private Map<Long, SysRoleMenu> loadRelationsByMenu(Long roleId) {
        QueryWrapper<SysRoleMenu> relationQuery = new QueryWrapper<>();
        relationQuery.eq("role_id", roleId);
        return baseMapper.selectList(relationQuery).stream()
                .collect(Collectors.toMap(SysRoleMenu::getMenuId, Function.identity(), (first, second) -> first));
    }

    /**
     * 按目标集合刷新已有关系：命中的恢复删除标记，未命中的置为删除。
     *
     * @param existingByMenu 已有关系
     * @param targetMenuIds  目标菜单主键，处理过程中会被消费
     * @param now            操作时间
     */
    private void refreshExistingRelations(Map<Long, SysRoleMenu> existingByMenu,
                                          Set<Long> targetMenuIds, LocalDateTime now) {
        for (SysRoleMenu existing : existingByMenu.values()) {
            boolean shouldEnable = targetMenuIds.remove(existing.getMenuId());
            int expectedFlag = shouldEnable ? 0 : 1;
            if (existing.getDeletedFlag() == null || existing.getDeletedFlag() != expectedFlag) {
                SysRoleMenu update = new SysRoleMenu();
                update.setId(existing.getId());
                update.setDeletedFlag(expectedFlag);
                if (shouldEnable && existing.getCreatedTime() == null) {
                    update.setCreatedTime(now);
                }
                if (baseMapper.updateById(update) != 1) {
                    throw new IllegalStateException("角色权限关系更新失败");
                }
            }
        }
    }

    /**
     * 为剩余目标菜单新增关系。
     *
     * @param roleId        角色主键
     * @param targetMenuIds 仍待新增的菜单主键
     * @param now           操作时间
     */
    private void insertNewRelations(Long roleId, Set<Long> targetMenuIds, LocalDateTime now) {
        for (Long menuId : targetMenuIds) {
            SysRoleMenu relation = new SysRoleMenu();
            relation.setRoleId(roleId);
            relation.setMenuId(menuId);
            relation.setCreatedTime(now);
            relation.setDeletedFlag(0);
            if (baseMapper.insert(relation) != 1) {
                throw new IllegalStateException("角色权限关系新增失败");
            }
        }
    }

    private QueryWrapper<SysRoleMenu> buildRoleMenuQuery(Long roleId, Long menuId) {
        QueryWrapper<SysRoleMenu> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (roleId != null) {
            wrapper.eq("role_id", roleId);
        }
        if (menuId != null) {
            wrapper.eq("menu_id", menuId);
        }
        wrapper.orderByDesc("id");
        return wrapper;
    }

    private boolean isSuperAdminRole(Long roleId) {
        if (roleId == null) {
            return false;
        }
        QueryWrapper<SysRole> wrapper = new QueryWrapper<>();
        wrapper.eq("role_id", roleId)
                .eq("role_code", "SUPER_ADMIN")
                .eq("deleted_flag", 0);
        return sysRoleMapper.selectCount(wrapper) > 0;
    }
}
