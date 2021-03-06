package com.dianrong.common.uniauth.server.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.ldap.AuthenticationException;
import org.springframework.stereotype.Service;

import com.dianrong.common.uniauth.common.bean.InfoName;
import com.dianrong.common.uniauth.common.bean.dto.IPAPermissionDto;
import com.dianrong.common.uniauth.common.bean.dto.PermissionDto;
import com.dianrong.common.uniauth.common.bean.dto.RoleDto;
import com.dianrong.common.uniauth.common.bean.dto.TenancyDto;
import com.dianrong.common.uniauth.common.bean.dto.UserDetailDto;
import com.dianrong.common.uniauth.common.bean.dto.UserDto;
import com.dianrong.common.uniauth.common.bean.request.LoginParam;
import com.dianrong.common.uniauth.common.cons.AppConstants;
import com.dianrong.common.uniauth.common.util.StringUtil;
import com.dianrong.common.uniauth.server.exp.AppException;
import com.dianrong.common.uniauth.server.ldap.ipa.dao.UserDao;
import com.dianrong.common.uniauth.server.ldap.ipa.entity.User;
import com.dianrong.common.uniauth.server.ldap.ipa.support.IPAUtil;
import com.dianrong.common.uniauth.server.service.cache.TenancyCache;
import com.dianrong.common.uniauth.server.util.BeanConverter;
import com.dianrong.common.uniauth.server.util.CheckEmpty;
import com.dianrong.common.uniauth.server.util.UniBundle;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import lombok.extern.slf4j.Slf4j;


/**
 * 处理IPA相关信息
 * 
 * @author wanglin
 */
@Service
@Slf4j
public class IPAService {

    @Autowired
    private UserDao userDao;

    @Autowired
    private TenancyCache tenancyCache;

    @Autowired
    private CommonService commonService;

    /**
     * UserService.login的IPA实现
     */
    public UserDto login(LoginParam loginParam) {
        Long defaultTenancyId = tenancyIdentityCheck(loginParam.getTenancyCode(), loginParam.getTenancyId(), loginParam.getAccount());
        try {
            userDao.authenticate(loginParam.getAccount(), loginParam.getPassword());
        } catch (AuthenticationException ae) {
            log.warn("IPA login, account {0}, password not match", ae);
            throw new AppException(InfoName.LOGIN_ERROR, UniBundle.getMsg("user.login.error"));
        } catch (EmptyResultDataAccessException eda) {
            log.warn("IPA login, account {0} not exsits", eda);
            throw new AppException(InfoName.LOGIN_ERROR_USER_NOT_FOUND, UniBundle.getMsg("user.login.notfound", loginParam.getAccount()));
        } catch (Exception e) {
            log.warn("IPA login, account {0} login failed", e);
            throw new AppException(InfoName.LOGIN_ERROR, UniBundle.getMsg("user.login.error"));
        }
        // load user basic information
        User user = userDao.getUserByAccount(loginParam.getAccount());
        UserDto dto = BeanConverter.convert(user);
        dto.setTenancyId(StringUtil.translateLongToInteger(defaultTenancyId));
        return dto;
    }

    /**
     * UserService.getUserDetailInfo的IPA实现
     */
    public UserDetailDto getUserDetailInfo(LoginParam loginParam) {
        CheckEmpty.checkEmpty(loginParam.getAccount(), "账号");
        Long tenancyId = tenancyIdentityCheck(loginParam.getTenancyCode(), loginParam.getTenancyId(), loginParam.getAccount());
        User user = userDao.getUserByAccount(loginParam.getAccount());
        if (user == null) {
            log.warn("Account {0} not found detail infomartion", loginParam.getAccount());
            return null;
        }
        UserDto dto = BeanConverter.convert(user);
        dto.setTenancyId(StringUtil.translateLongToInteger(tenancyId));
        UserDetailDto userDetailDto = new UserDetailDto();
        userDetailDto.setUserDto(dto);
        userDetailDto.setIpaPermissionDto(constructIPAPermission(user.getGroups()));
        return userDetailDto;
    }
    

    /**
     * UserService.getUserByEmailOrPhone的IPA实现
     */
    public UserDto getUserByEmailOrPhone(LoginParam loginParam) {
        throw new AppException(InfoName.BAD_REQUEST, UniBundle.getMsg("user.info.load.ipa.account", loginParam.getAccount()));
    }

    // TODO UserService.vpnLogin的IPA实现

    /**
     * 所有IPA相关的操作都当做是DIANRONG租户来处理
     * 
     * @param tenancyCode 传入的租户编码
     * @param tenancyId 传入的租户id
     * @param account 账号信息
     * @return 默认的租户id
     */
    private Long tenancyIdentityCheck(String tenancyCode, Long tenancyId, String account) {
        TenancyDto defaultTenancy = tenancyCache.getEnableTenancyByCode(AppConstants.DEFAULT_TANANCY_CODE);
        if (!defaultTenancy.getCode().equals(tenancyCode) && !defaultTenancy.getId().equals(tenancyId)) {
            throw new AppException(InfoName.LOGIN_ERROR_USER_NOT_FOUND, UniBundle.getMsg("user.login.notfound", account));
        }
        return defaultTenancy.getId();
    }

    /**
     * 根据IPA的组信息构造一个uniauth使用的权限信息
     */
    private IPAPermissionDto constructIPAPermission(List<String> ipaGroups) {
        Set<String> groupCodes = IPAUtil.translateMemberGroupToGroupName(ipaGroups);
        IPAPermissionDto ipaPermission = new IPAPermissionDto();
        if (groupCodes.isEmpty()) {
            return ipaPermission;
        }
        TenancyDto defaultTenancy = tenancyCache.getEnableTenancyByCode(AppConstants.DEFAULT_TANANCY_CODE);
        String tenancyCode = defaultTenancy.getCode();
        Integer tenancyId = StringUtil.translateLongToInteger(defaultTenancy.getId());
        Integer roleCodeId = commonService.getRoleCodeId(AppConstants.ROLE_CODE_ROLE_NORMAL);
        Integer permTypeId = commonService.getPermTypeId(AppConstants.PERM_TYPE_PRIVILEGE);

        // 统一设置成privilege类型权限
        String permType = AppConstants.PERM_TYPE_PRIVILEGE;
        List<RoleDto> roleList = Lists.newArrayList();
        for (String groupCode : groupCodes) {
            RoleDto roleDto = new RoleDto();
            Map<String, Set<String>> permMap = Maps.newHashMap();
            Map<String, Set<PermissionDto>> permDtoMap = Maps.newHashMap();
            roleDto.setDescription(groupCode).setName(groupCode).setRoleCode(groupCode).setRoleCodeId(roleCodeId).setStatus(AppConstants.STATUS_ENABLED).setPermMap(permMap)
                    .setPermDtoMap(permDtoMap).setTenancyCode(tenancyCode).setTenancyId(tenancyId);
            Set<String> permissionStr = Sets.newHashSet();
            permissionStr.add(groupCode);
            permMap.put(permType, permissionStr);
            Set<PermissionDto> permission = Sets.newHashSet();
            PermissionDto pdto = new PermissionDto();
            pdto.setDescription(groupCode).setPermType(permType).setPermTypeId(permTypeId).setStatus(AppConstants.STATUS_ENABLED).setValue(groupCode).setTenancyCode(tenancyCode)
                    .setTenancyId(tenancyId);
            permission.add(pdto);
            permDtoMap.put(permType, permission);
            roleList.add(roleDto);
        }
        ipaPermission.setRoleList(roleList);
        ipaPermission.setTenancyCode(tenancyCode);
        ipaPermission.setTenancyId(tenancyId);
        return ipaPermission;
    }
}
