package com.neuedu.ems.service.impl.sys;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.neuedu.ems.entity.sys.SysRole;
import com.neuedu.ems.mapper.sys.SysRoleMapper;
import com.neuedu.ems.service.sys.PermissionService;
import com.neuedu.ems.service.sys.SysRoleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 系统角色 Service 实现类。
 */
@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {

    private static final String SUPER_ADMIN_ROLE = "SUPER_ADMIN";

    @Autowired
    private PermissionService permissionService;

    @Override
    public Page<SysRole> pageRoles(Integer current, Integer size, String keyword, String status) {
        Page<SysRole> page = new Page<>(current, size);
        return baseMapper.selectPage(page, buildRoleQuery(keyword, status));
    }

    @Override
    public SysRole getRoleById(Long roleId) {
        QueryWrapper<SysRole> wrapper = new QueryWrapper<>();
        wrapper.eq("role_id", roleId);
        wrapper.eq("deleted_flag", 0);
        return baseMapper.selectOne(wrapper);
    }

    @Override
    public boolean saveRole(SysRole role) {
        if (SUPER_ADMIN_ROLE.equals(role.getRoleCode())) {
            return false;
        }
        if (role.getDeletedFlag() == null) {
            role.setDeletedFlag(0);
        }
        boolean saved = baseMapper.insert(role) > 0;
        if (saved) {
            permissionService.evictAll();
        }
        return saved;
    }

    @Override
    public boolean updateRole(SysRole role) {
        SysRole existing = loadUpdatableRole(role);
        boolean updated = existing != null && persistRole(role, existing);
        if (updated) {
            permissionService.evictAll();
        }
        return updated;
    }

    /**
     * 读取可修改的角色，超管角色要额外校验不能被降级、停用或缩小数据范围。
     *
     * @param role 待修改的角色
     * @return 可修改时返回库内当前值，否则返回 null
     */
    private SysRole loadUpdatableRole(SysRole role) {
        if (role.getRoleId() == null) {
            return null;
        }
        SysRole existing = getRoleById(role.getRoleId());
        if (existing == null || !keepsSuperAdminInvariant(existing, role)) {
            return null;
        }
        return existing;
    }

    /**
     * 超管角色不允许改编码、停用或缩小数据范围，其它角色一律放行。
     *
     * @param existing 库内角色
     * @param role     待修改的角色
     * @return 是否满足超管约束
     */
    private boolean keepsSuperAdminInvariant(SysRole existing, SysRole role) {
        if (!SUPER_ADMIN_ROLE.equals(existing.getRoleCode())) {
            return true;
        }
        boolean changesCode = StringUtils.hasText(role.getRoleCode())
                && !SUPER_ADMIN_ROLE.equals(role.getRoleCode());
        boolean disables = StringUtils.hasText(role.getStatus()) && !"ENABLE".equals(role.getStatus());
        boolean shrinksScope = StringUtils.hasText(role.getDataScope()) && !"ALL".equals(role.getDataScope());
        return !(changesCode || disables || shrinksScope);
    }

    /**
     * 落库更新，超管角色先强制回填编码、状态与数据范围。
     *
     * @param role     待修改的角色
     * @param existing 库内角色
     * @return 是否更新成功
     */
    private boolean persistRole(SysRole role, SysRole existing) {
        if (SUPER_ADMIN_ROLE.equals(existing.getRoleCode())) {
            role.setRoleCode(SUPER_ADMIN_ROLE);
            role.setStatus("ENABLE");
            role.setDataScope("ALL");
        }
        role.setDeletedFlag(null);
        return baseMapper.updateById(role) > 0;
    }

    @Override
    public boolean removeRoleById(Long roleId) {
        SysRole existing = getRoleById(roleId);
        if (existing == null || SUPER_ADMIN_ROLE.equals(existing.getRoleCode())) {
            return false;
        }
        SysRole role = new SysRole();
        role.setRoleId(roleId);
        role.setDeletedFlag(1);
        boolean removed = baseMapper.updateById(role) > 0;
        if (removed) {
            permissionService.evictAll();
        }
        return removed;
    }

    @Override
    public List<SysRole> listRoles(String keyword, String status) {
        return baseMapper.selectList(buildRoleQuery(keyword, status));
    }

    private QueryWrapper<SysRole> buildRoleQuery(String keyword, String status) {
        QueryWrapper<SysRole> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted_flag", 0);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(item -> item.like("role_code", keyword).or().like("role_name", keyword));
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq("status", status);
        }
        wrapper.orderByDesc("role_id");
        return wrapper;
    }
}
