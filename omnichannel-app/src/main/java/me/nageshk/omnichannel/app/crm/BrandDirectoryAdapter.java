package me.nageshk.omnichannel.app.crm;

import java.util.List;
import java.util.Optional;
import me.nageshk.omnichannel.crm.platform.BrandDirectory;
import me.nageshk.omnichannel.crm.platform.PlatformBrand;
import me.nageshk.omnichannel.db.tenant.CustomerRepository;
import me.nageshk.omnichannel.db.tenant.Workspace;
import me.nageshk.omnichannel.db.tenant.WorkspaceRepository;
import org.springframework.stereotype.Component;

@Component
class BrandDirectoryAdapter implements BrandDirectory {

    private final WorkspaceRepository workspaces;
    private final CustomerRepository customers;

    BrandDirectoryAdapter(WorkspaceRepository workspaces, CustomerRepository customers) {
        this.workspaces = workspaces;
        this.customers = customers;
    }

    @Override
    public Optional<PlatformBrand> find(String workspaceId) {
        return workspaces.findById(workspaceId).map(BrandDirectoryAdapter::of);
    }

    @Override
    public Optional<PlatformBrand> findByNames(String customerName, String brandName) {
        return customers.findFirstByName(customerName)
                .flatMap(c -> workspaces.findFirstByCustomerIdAndName(c.getId(), brandName))
                .map(BrandDirectoryAdapter::of);
    }

    @Override
    public List<String> allBrandIds() {
        return workspaces.findAll().stream().map(Workspace::getId).toList();
    }

    private static PlatformBrand of(Workspace w) {
        return new PlatformBrand(w.getId(), w.getCustomerId(), w.getName());
    }
}
