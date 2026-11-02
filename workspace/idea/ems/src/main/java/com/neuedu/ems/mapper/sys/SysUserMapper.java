package com.neuedu.ems.mapper.sys;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.neuedu.ems.entity.sys.SysUser;
import com.neuedu.ems.vo.sys.NotificationRecipientAccount;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 系统用户Mapper接口
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    // MyBatis-Plus已提供基础CRUD方法
    // 自定义查询方法可以写在这里

    /**
     * 根据用户名查询用户
     */
    @Select("select * from sys_user where username = #{username} and deleted_flag = 0")
    SysUser getByUserName(@Param("username") String username);

    @Update("""
            update sys_user
            set status = case when login_fail_count + 1 >= #{maxAttempts} then 'LOCKED' else status end,
                locked_time = case when login_fail_count + 1 >= #{maxAttempts} then now() else locked_time end,
                login_fail_count = login_fail_count + 1,
                updated_time = now(),
                version = version + 1
            where user_id = #{userId} and status = 'ENABLE' and deleted_flag = 0
            """)
    int recordLoginFailure(@Param("userId") Long userId, @Param("maxAttempts") int maxAttempts);

    @Update("""
            update sys_user
            set login_fail_count = 0,
                locked_time = null,
                last_login_time = #{loginTime},
                updated_by = user_id,
                updated_time = #{loginTime},
                version = version + 1
            where user_id = #{userId} and status = 'ENABLE' and deleted_flag = 0
            """)
    int recordLoginSuccess(@Param("userId") Long userId, @Param("loginTime") LocalDateTime loginTime);

    @Update("""
            update sys_user
            set password_hash = #{passwordHash}, updated_by = user_id, updated_time = now(), version = version + 1
            where user_id = #{userId} and deleted_flag = 0
            """)
    int upgradePasswordHash(@Param("userId") Long userId, @Param("passwordHash") String passwordHash);

    @Select("""
            select distinct r.role_code
            from sys_user_role ur
            join sys_role r on r.role_id = ur.role_id and r.deleted_flag = 0 and r.status = 'ENABLE'
            where ur.user_id = #{userId} and ur.deleted_flag = 0
            order by r.role_code
            """)
    List<String> listRoleCodes(@Param("userId") Long userId);

    @Select("""
            select distinct m.permission_code
            from sys_user_role ur
            join sys_role r on r.role_id = ur.role_id and r.deleted_flag = 0 and r.status = 'ENABLE'
            join sys_role_menu rm on rm.role_id = r.role_id and rm.deleted_flag = 0
            join sys_menu m on m.menu_id = rm.menu_id and m.deleted_flag = 0
            where ur.user_id = #{userId} and ur.deleted_flag = 0
              and m.permission_code is not null and m.permission_code <> ''
            order by m.permission_code
            """)
    List<String> listPermissionCodes(@Param("userId") Long userId);

    @Select("""
            select distinct r.data_scope
            from sys_user_role ur
            join sys_role r on r.role_id = ur.role_id and r.deleted_flag = 0 and r.status = 'ENABLE'
            where ur.user_id = #{userId} and ur.deleted_flag = 0
              and r.data_scope is not null and r.data_scope <> ''
            """)
    List<String> listDataScopes(@Param("userId") Long userId);

    @Select("""
            select e.department_id
            from sys_user u
            join org_employee e on e.employee_id = u.employee_id and e.deleted_flag = 0
            where u.user_id = #{userId} and u.deleted_flag = 0
            limit 1
            """)
    Long getDepartmentIdByUserId(@Param("userId") Long userId);

    @Select("""
            select distinct member.team_id
            from sys_user u
            join org_team_member member
              on member.employee_id = u.employee_id and member.deleted_flag = 0 and member.status = 'ENABLE'
            join org_team team on team.team_id = member.team_id and team.deleted_flag = 0
            where u.user_id = #{userId} and u.deleted_flag = 0
            order by member.team_id
            """)
    List<Long> listTeamIdsByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT DISTINCT user.user_id, user.employee_id
            FROM sys_user user
            JOIN org_employee employee
              ON employee.employee_id = user.employee_id
             AND employee.deleted_flag = 0 AND employee.status = 'ENABLE'
            JOIN sys_user_role user_role
              ON user_role.user_id = user.user_id AND user_role.deleted_flag = 0
            JOIN sys_role role
              ON role.role_id = user_role.role_id
             AND role.deleted_flag = 0 AND role.status = 'ENABLE'
            LEFT JOIN sys_role_menu role_menu
              ON role_menu.role_id = role.role_id AND role_menu.deleted_flag = 0
            LEFT JOIN sys_menu menu
              ON menu.menu_id = role_menu.menu_id AND menu.deleted_flag = 0
            WHERE user.employee_id = #{employeeId}
              AND user.status = 'ENABLE' AND user.deleted_flag = 0
              AND (role.role_code = 'SUPER_ADMIN' OR menu.permission_code = #{requiredPermission})
            ORDER BY user.user_id
            """)
    List<NotificationRecipientAccount> listNotificationRecipients(
            @Param("employeeId") Long employeeId,
            @Param("requiredPermission") String requiredPermission);

    /**
     * 列出全部有效的超级管理员账号，作为责任收件人缺失时的兜底接收人。
     * <p>
     * 业务责任通知按"部门主管 + 业务查看权限"路由。当责任部门没有配置主管、
     * 主管没有登录账号、或主管缺少对应业务权限时，收件人会解析为空，
     * 事件会以 {@code NO_VALID_NOTIFICATION_RECIPIENT} 反复失败并最终停止重试，
     * 通知就此静默丢失。兜底给超级管理员至少保证有人能看到并转派，
     * 而不是让事件烂在 outbox 里。
     * </p>
     * <p>
     * 账号 {@code admin} 与 {@code PermissionServiceImpl} 的处理保持一致：
     * 该账号在权限上下文里始终被视为 {@code SUPER_ADMIN}，即使没有
     * {@code sys_user_role} 记录（初始化数据只建账号不建角色时就是这样），
     * 所以这里也把它算作兜底接收人，避免整个库没有任何 {@code SUPER_ADMIN}
     * 角色绑定导致兜底同样落空。
     * </p>
     *
     * @return 超级管理员账号列表；没有可用账号时返回空列表
     */
    @Select("""
            SELECT DISTINCT user.user_id, user.employee_id
            FROM sys_user user
            LEFT JOIN sys_user_role user_role
              ON user_role.user_id = user.user_id AND user_role.deleted_flag = 0
            LEFT JOIN sys_role role
              ON role.role_id = user_role.role_id
             AND role.deleted_flag = 0 AND role.status = 'ENABLE'
            WHERE user.status = 'ENABLE' AND user.deleted_flag = 0
              AND (role.role_code = 'SUPER_ADMIN' OR user.username = 'admin')
            ORDER BY user.user_id
            """)
    List<NotificationRecipientAccount> listSuperAdminRecipients();
}
