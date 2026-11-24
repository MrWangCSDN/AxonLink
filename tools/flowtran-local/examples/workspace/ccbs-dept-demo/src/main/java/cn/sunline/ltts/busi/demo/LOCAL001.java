package cn.sunline.ltts.busi.demo;

public class LOCAL001 {
    public void prepare() {
        validate();
    }

    private void validate() {
        var transfer = SysUtil.getInstance(TransferService.class);
        transfer.post();
    }

    public void postDirect() {
        SysUtil.getRemoteInstance(TransferService.class).post();
    }

    public void audit() {
        LegacyService legacy = SysUtil.getInstance(LegacyService.class);
        legacy.audit();
        this.writeAudit();
    }

    private void writeAudit() {}
}

interface TransferService {
    void post();
}

class TransferServiceBase implements TransferService {
    private final CheckComponentImpl checker = new CheckComponentImpl();

    @Override
    public void post() {
        checker.verify();
    }
}

class TransferServiceImpl extends TransferServiceBase {
    @Override
    public void post() {
        super.post();
    }
}

interface CheckComponent {
    void verify();
}

class CheckComponentImpl implements CheckComponent {
    @Override
    public void verify() {
        SysUtil.getInstance(CustomerLookup.class).load();
    }
}

interface CustomerLookup {
    void load();
}

class CustomerLookupImpl implements CustomerLookup {
    @Override
    public void load() {
        LedgerTables.CustomerDao.selectByKey();
    }
}

interface LegacyService {
    void audit();
}

class LegacyServiceImpl implements LegacyService {
    @Override
    public void audit() {}
}

class LedgerTables {
    static class CustomerDao {
        static void selectByKey() {}
    }
}

class SysUtil {
    static <T> T getInstance(Class<T> type) {
        throw new UnsupportedOperationException("仅供源码扫描");
    }

    static <T> T getRemoteInstance(Class<T> type) {
        return getInstance(type);
    }
}
